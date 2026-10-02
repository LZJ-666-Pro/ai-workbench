package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.springframework.transaction.annotation.Isolation;
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

    /**
     * 第二段：只有确认接口能调。归属校验 → 过期检查 → 账户行锁 → 幂等 CAS → 规则复检 → 划款，同一事务。
     *
     * 并发与越权这两件事都在这一个方法里解决：
     *   1) 确认单按 confirm_id + memory_id 双条件查：拿到别人的确认码也动不了钱；
     *      员工身份在这里再兜一次底（工具层没给它 transfer，但确认接口是 HTTP 入口，必须自己拦）。
     *   2) 进临界区前对付款账户行加排他锁（SELECT ... FOR UPDATE），同一账户的确认被串行化；
     *      隔离级别取 READ COMMITTED，使复检读到的日累计/余额是「上一笔确认刚提交」的最新值。
     *      REPEATABLE READ 下普通 SELECT 会一直用事务开始时的快照，并发确认会各自通过复检，
     *      日限额形同虚设——这是必须显式声明隔离级别的原因。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult confirmOrder(String memoryId, String confirmId, boolean confirm) {
        BankIdentity identity = BankIdentity.fromMemoryId(memoryId);
        if (!identity.canTransfer()) {
            audit.record(memoryId, "transfer.confirm", "员工身份尝试操作确认单 " + confirmId, "DENY");
            return TransferResult.deny("内部员工账号没有资金操作权限，无法操作转账确认单。");
        }
        Optional<OrderRow> found = jdbc.query("""
                SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                FROM bank_transfer_order WHERE confirm_id = ? AND memory_id = ?
                """, rowMapper(), confirmId, memoryId).stream().findFirst();
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

        // 临界区入口：锁住付款账户行。同一账户的并发确认在此排队，
        // 排在后面的事务因此能看到前一笔已提交的结果（日累计与余额都是最新值）。
        Optional<Account> from = lockAccount(order.fromAccount());
        if (from.isEmpty()) {
            // 付款账户已不存在：必须显式落到 REJECTED，绝不能让单据停在
            // 「状态是 EXECUTED、钱却一分没动」的不一致终态上
            jdbc.update("UPDATE bank_transfer_order SET status = 'REJECTED' WHERE id = ? AND status = 'PENDING'",
                    order.id());
            audit.record(memoryId, "transfer.confirm",
                    "确认单 %s 付款账户不存在".formatted(confirmId), "FAIL");
            return TransferResult.fail("付款账户不存在，转账未执行。");
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
        RiskDecision decision = TransferRiskRules.check(
                identity, order.toAccount(), order.amount(),
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

        // 钱真正动了的时刻才写 executed_at：日累计按执行时间归集，
        // 避免「23:59 建单、次日 00:01 确认」被算到昨天而让今天额度被重复使用
        jdbc.update("UPDATE bank_transfer_order SET executed_at = NOW() WHERE id = ?", order.id());

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

    /**
     * 供 queryTransferOrder 工具使用：把确认单的真实状态（含过期判定）讲给模型听。
     * 传了确认码也按「本会话」限定查询——确认码泄漏时，别的会话也读不到这张单。
     */
    public String describeOrder(String memoryId, String confirmId) {
        boolean byId = confirmId != null && !confirmId.isBlank()
                && !"null".equalsIgnoreCase(confirmId.trim()) && !"-".equals(confirmId.trim());
        List<OrderRow> rows = byId
                ? jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                        FROM bank_transfer_order WHERE confirm_id = ? AND memory_id = ?
                        """, rowMapper(), confirmId.trim(), memoryId)
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

    /**
     * 当日已执行的转账累计。
     *
     * 两个口径都固定在数据库侧：
     *   - 按 executed_at（钱真正动了的时刻）而不是 created_at 归集，堵住「跨零点建单」绕过日限额；
     *   - 日期边界用 CURDATE() 由 MySQL 按连接时区（Asia/Shanghai）判定，
     *     不取 JVM 的 LocalDate.now()——否则容器跑 UTC 时「今天」会从北京时间 08:00 才开始，
     *     每天多出 8 小时窗口让限额被重复使用，且与后台管理端的 CURDATE() 统计口径不一致。
     */
    private BigDecimal transferredToday(String accountNo) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM bank_transfer_order
                WHERE from_account = ? AND status = 'EXECUTED' AND executed_at >= CURDATE()
                """, BigDecimal.class, accountNo);
    }

    /** 取付款账户并加行锁（FOR UPDATE）：并发确认在同一账户上串行，后续复检才读得到最新余额 */
    private Optional<Account> lockAccount(String accountNo) {
        List<Account> found = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE account_no = ? FOR UPDATE",
                (rs, i) -> new Account(rs.getString("account_no"), rs.getString("owner"),
                        rs.getBigDecimal("balance"), List.of()),
                accountNo);
        return found.stream().findFirst();
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
