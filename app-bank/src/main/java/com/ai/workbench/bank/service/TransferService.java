package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.risk.RiskDecision;
import com.ai.workbench.bank.risk.TransferRiskRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 转账执行服务（两段式 HITL）：
 *
 *   createPendingOrder —— 模型可调用：只做规则预检 + 创建 PENDING 确认单，不碰钱
 *   confirmOrder       —— 只有确认接口能调：状态机 CAS 抢占（幂等）→ 规则复检 → 划款
 *
 * 「读宽写窄」的写侧实现：资金变动的唯一入口是人类对确认卡片的操作。
 */
@Service
public class TransferService {

    /** 确认单有效期（分钟），超时确认会被拒绝并标记 REJECTED */
    @Value("${bank.transfer-confirm-ttl-minutes:10}")
    private long confirmTtlMinutes;

    private final JdbcTemplate jdbc;
    private final ToolAuditLogger audit;

    public TransferService(JdbcTemplate jdbc, ToolAuditLogger audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * 结果分类：PENDING 已创建待确认 / SUCCESS 执行成功 / DENY 被规则拒绝 / FAIL 系统性失败。
     * message 面向最终用户（LLM 会原样转述），拒绝与失败都不抛异常、不重试。
     */
    public record TransferResult(String kind, String message) {

        static TransferResult pending(String message) {
            return new TransferResult("PENDING", message);
        }

        static TransferResult success(String message) {
            return new TransferResult("SUCCESS", message);
        }

        static TransferResult deny(String message) {
            return new TransferResult("DENY", message);
        }

        static TransferResult fail(String message) {
            return new TransferResult("FAIL", message);
        }
    }

    /** 推给前端确认卡片的数据 */
    public record PendingOrder(String confirmId, String fromAccount, String toAccount,
                               String toOwner, BigDecimal amount, String reason) {

    }

    /** 模型可调用的第一段：预检 + 建 PENDING 单，不碰钱。付款账户由服务对象身份决定 */
    public TransferResult createPendingOrder(String memoryId, String toAccountOrOwner,
                                             BigDecimal amount, String reason) {
        BankIdentity identity = BankIdentity.fromMemoryId(memoryId);
        if (!identity.canTransfer()) {
            audit.record(memoryId, "transfer", "员工身份尝试发起转账", "DENY");
            return TransferResult.deny("内部员工账号没有资金操作权限，无法发起转账。");
        }
        Optional<Account> from = findAccount(identity.boundAccount());
        if (from.isEmpty()) {
            return TransferResult.fail("付款账户不存在");
        }
        Optional<Account> to = findAccount(toAccountOrOwner);
        if (to.isEmpty()) {
            return TransferResult.fail("未找到收款账户「%s」".formatted(toAccountOrOwner));
        }
        if (from.get().accountNo().equals(to.get().accountNo())) {
            return TransferResult.deny("不能向本人账户转账");
        }

        RiskDecision decision = TransferRiskRules.check(
                identity, to.get().accountNo(), amount, from.get().balance(),
                transferredToday(from.get().accountNo()));
        if (!decision.allowed()) {
            audit.record(memoryId, "transfer",
                    "预检拒绝: to=%s, amount=%s".formatted(to.get().accountNo(), amount), "DENY");
            return TransferResult.deny("转账被风控拦截：" + decision.reason());
        }

        String confirmId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO bank_transfer_order (confirm_id, memory_id, from_account, to_account, amount, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                """, confirmId, memoryId, from.get().accountNo(), to.get().accountNo(), amount,
                reason == null || reason.isBlank() ? null : reason);
        audit.record(memoryId, "transfer",
                "创建确认单 %s: to=%s, amount=%s".formatted(confirmId, to.get().accountNo(), amount), "PENDING");
        return TransferResult.pending(
                "转账确认单已创建（确认码 %s）：%s（%s）→ %s（%s），金额 %.2f 元，等待用户确认。"
                        .formatted(confirmId, from.get().accountNo(), from.get().owner(),
                                to.get().accountNo(), to.get().owner(), amount));
    }

    /** 底座流结束时回调：取本会话还没推送过卡片的确认单，并标记已推送（避免每轮都重发） */
    public List<PendingOrder> takeUnnotifiedPending(String memoryId) {
        List<PendingOrder> orders = jdbc.query("""
                SELECT confirm_id, from_account, to_account, amount, reason
                FROM bank_transfer_order
                WHERE memory_id = ? AND status = 'PENDING' AND notified = 0
                ORDER BY id DESC LIMIT 5
                """, (rs, i) -> new PendingOrder(
                        rs.getString("confirm_id"),
                        rs.getString("from_account"),
                        rs.getString("to_account"),
                        ownerOf(rs.getString("to_account")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason")),
                memoryId);
        for (PendingOrder order : orders) {
            jdbc.update("UPDATE bank_transfer_order SET notified = 1 WHERE confirm_id = ?", order.confirmId());
        }
        return orders;
    }

    /** 第二段：只有确认接口能调。幂等 CAS + 过期检查 + 规则复检 + 划款，同一事务 */
    @Transactional
    public TransferResult confirmOrder(String memoryId, String confirmId, boolean confirm) {
        Optional<OrderRow> found = jdbc.query("""
                SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                FROM bank_transfer_order WHERE confirm_id = ?
                """, (rs, i) -> new OrderRow(
                        rs.getLong("id"),
                        rs.getString("confirm_id"),
                        rs.getString("from_account"),
                        rs.getString("to_account"),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at")),
                confirmId).stream().findFirst();
        if (found.isEmpty()) {
            return TransferResult.fail("确认单不存在：%s".formatted(confirmId));
        }
        OrderRow order = found.get();

        // 取消：同样走 CAS，只有 PENDING 能取消
        if (!confirm) {
            int cancelled = jdbc.update(
                    "UPDATE bank_transfer_order SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'",
                    order.id());
            audit.record(memoryId, "transfer.confirm", "取消确认单 " + confirmId,
                    cancelled > 0 ? "CANCELLED" : "ALREADY_DONE");
            return cancelled > 0
                    ? TransferResult.pending("转账已取消，资金未变动。")
                    : TransferResult.pending("该确认单已处理过（状态 %s），取消无效。".formatted(order.status()));
        }

        // 过期检查：确认单超时未确认视为失效
        long ageMinutes = (System.currentTimeMillis() - order.createdAt().getTime()) / 60000;
        if (ageMinutes > confirmTtlMinutes) {
            jdbc.update("UPDATE bank_transfer_order SET status = 'REJECTED' WHERE id = ? AND status = 'PENDING'",
                    order.id());
            audit.record(memoryId, "transfer.confirm",
                    "确认单 %s 已过期（%d 分钟）".formatted(confirmId, ageMinutes), "DENY");
            return TransferResult.deny("确认单已过期（有效期 %d 分钟），请重新发起转账。".formatted(confirmTtlMinutes));
        }

        // 幂等核心：状态机 CAS。并发/重复点击下只有一个请求能把 PENDING 推进到 EXECUTED
        int claimed = jdbc.update(
                "UPDATE bank_transfer_order SET status = 'EXECUTED' WHERE id = ? AND status = 'PENDING'",
                order.id());
        if (claimed == 0) {
            audit.record(memoryId, "transfer.confirm", "重复提交确认单 " + confirmId, "IDEMPOTENT_SKIP");
            return TransferResult.pending("该确认单已执行过，本次重复操作被幂等拦截，资金未变动。");
        }

        // 规则复检：卡片展示期间余额/日累计可能已被其他交易改变（限额按会话身份分档）
        Optional<Account> from = findAccount(order.fromAccount());
        if (from.isEmpty()) {
            return TransferResult.fail("付款账户不存在");
        }
        RiskDecision decision = TransferRiskRules.check(
                BankIdentity.fromMemoryId(memoryId), order.toAccount(), order.amount(),
                from.get().balance(), transferredToday(order.fromAccount()));
        if (!decision.allowed()) {
            jdbc.update("UPDATE bank_transfer_order SET status = 'REJECTED' WHERE id = ?", order.id());
            audit.record(memoryId, "transfer.confirm",
                    "确认单 %s 复检失败: %s".formatted(confirmId, decision.reason()), "DENY");
            return TransferResult.deny("确认时被风控拦截：" + decision.reason());
        }

        // 乐观扣款：balance >= ? 保证并发下不扣成负数；失败则整个事务回滚（CAS 也会回滚，单子回到 PENDING）
        int debited = jdbc.update(
                "UPDATE bank_account SET balance = balance - ? WHERE account_no = ? AND balance >= ?",
                order.amount(), order.fromAccount(), order.amount());
        if (debited == 0) {
            jdbc.update("UPDATE bank_transfer_order SET status = 'REJECTED' WHERE id = ?", order.id());
            audit.record(memoryId, "transfer.confirm", "确认单 %s 余额不足".formatted(confirmId), "DENY");
            return TransferResult.deny("余额不足，转账未执行。");
        }
        jdbc.update("UPDATE bank_account SET balance = balance + ? WHERE account_no = ?",
                order.amount(), order.toAccount());

        Optional<Account> to = findAccount(order.toAccount());
        jdbc.update("INSERT INTO bank_transaction (account_no, amount, description) VALUES (?, ?, ?)",
                order.fromAccount(), order.amount().negate(),
                "转账给 %s（%s）-%.2f 元%s".formatted(to.map(Account::owner).orElse("?"),
                        order.toAccount(), order.amount(), note(order.reason())));
        jdbc.update("INSERT INTO bank_transaction (account_no, amount, description) VALUES (?, ?, ?)",
                order.toAccount(), order.amount(),
                "收到 %s（%s）转账 +%.2f 元%s".formatted(from.get().owner(),
                        order.fromAccount(), order.amount(), note(order.reason())));

        BigDecimal newBalance = jdbc.queryForObject(
                "SELECT balance FROM bank_account WHERE account_no = ?",
                BigDecimal.class, order.fromAccount());
        audit.record(memoryId, "transfer.confirm",
                "确认单 %s 执行: %s -> %s, %.2f".formatted(confirmId, order.fromAccount(),
                        order.toAccount(), order.amount()), "SUCCESS");
        return TransferResult.success("转账成功：%s（%s）→ %s（%s），金额 %.2f 元，付款方剩余余额 %.2f 元"
                .formatted(order.fromAccount(), from.get().owner(), order.toAccount(),
                        to.map(Account::owner).orElse("?"), order.amount(), newBalance));
    }

    /** 供 queryTransferOrder 工具使用：把确认单的真实状态（含过期判定）讲给模型听 */
    public String describeOrder(String memoryId, String confirmId) {
        boolean byId = confirmId != null && !confirmId.isBlank()
                && !"null".equalsIgnoreCase(confirmId.trim()) && !"-".equals(confirmId.trim());
        List<OrderRow> rows = byId
                ? jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                        FROM bank_transfer_order WHERE confirm_id = ?
                        """, rowMapper(), confirmId.trim())
                : jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                        FROM bank_transfer_order WHERE memory_id = ? ORDER BY id DESC LIMIT 1
                        """, rowMapper(), memoryId);
        if (rows.isEmpty()) {
            return "没有找到转账确认单。";
        }
        OrderRow order = rows.get(0);
        long ageMinutes = (System.currentTimeMillis() - order.createdAt().getTime()) / 60000;
        String effectiveStatus = order.status();
        if ("PENDING".equals(effectiveStatus) && ageMinutes > confirmTtlMinutes) {
            effectiveStatus = "EXPIRED";
        }
        String summary = "确认单 %s：%s → %s，金额 %.2f 元%s，创建于 %tF %<tR，当前状态：%s".formatted(
                order.confirmId(), order.fromAccount(), order.toAccount(), order.amount(),
                order.reason() == null ? "" : "，附言「%s」".formatted(order.reason()),
                order.createdAt(), effectiveStatus);
        audit.record(memoryId, "queryTransferOrder",
                "查询确认单 %s: %s".formatted(order.confirmId(), effectiveStatus), "SUCCESS");
        return switch (effectiveStatus) {
            case "PENDING" -> summary + "（待确认，请在页面的确认卡片上操作，有效期还剩约 "
                    + Math.max(0, confirmTtlMinutes - ageMinutes) + " 分钟）";
            case "EXPIRED" -> summary + "（已过期无法确认，请让用户重新发起转账，你将创建新的确认单）";
            case "EXECUTED" -> summary + "（已执行成功，资金已划转）";
            case "CANCELLED" -> summary + "（已取消，需要时请重新发起转账）";
            case "REJECTED" -> summary + "（已被拒绝或风控拦截，请重新发起）";
            default -> summary;
        };
    }

    private org.springframework.jdbc.core.RowMapper<OrderRow> rowMapper() {
        return (rs, i) -> new OrderRow(
                rs.getLong("id"),
                rs.getString("confirm_id"),
                rs.getString("from_account"),
                rs.getString("to_account"),
                rs.getBigDecimal("amount"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getTimestamp("created_at"));
    }

    private BigDecimal transferredToday(String accountNo) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM bank_transfer_order
                WHERE from_account = ? AND status = 'EXECUTED' AND created_at >= ?
                """, BigDecimal.class, accountNo, Timestamp.valueOf(LocalDate.now().atStartOfDay()));
    }

    private String ownerOf(String accountNo) {
        return jdbc.queryForObject(
                "SELECT owner FROM bank_account WHERE account_no = ?", String.class, accountNo);
    }

    private String note(String reason) {
        return reason == null || reason.isBlank() ? "" : "，附言「%s」".formatted(reason);
    }

    /** 只取账户本身（不查明细），账号/户名精确匹配，优先账号 */
    private Optional<Account> findAccount(String accountNoOrOwner) {
        List<Account> found = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE account_no = ? OR owner = ? LIMIT 1",
                (rs, i) -> new Account(rs.getString("account_no"), rs.getString("owner"),
                        rs.getBigDecimal("balance"), List.of()),
                accountNoOrOwner, accountNoOrOwner);
        return found.stream().findFirst();
    }

    private record OrderRow(Long id, String confirmId, String fromAccount, String toAccount,
                            BigDecimal amount, String reason, String status, Timestamp createdAt) {

    }
}
