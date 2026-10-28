package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.risk.RiskDecision;
import com.ai.workbench.bank.risk.TransferRiskProperties;
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

    /**
     * 支付密码（演示桩）：确认转账时的二次验证因子，生产应换成密码卡片/短信动态码。
     * 密码校验放在 Service 而不是控制器：试错计数与作废要和确认单状态在同一个事务里推进，
     * 控制器没有事务上下文，做不了「第 3 次错误 → 单子作废」的原子推进。
     */
    @Value("${bank.pay-password:123456}")
    private String payPassword;

    /** 同一确认单支付密码最大试错次数，超过即作废（堵暴力枚举） */
    private static final int MAX_PWD_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final ToolAuditLogger audit;
    private final TransferRiskProperties riskProps;

    public TransferService(JdbcTemplate jdbc, ToolAuditLogger audit, TransferRiskProperties riskProps) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.riskProps = riskProps;
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

    /**
     * 会话交易单视图（前端恢复卡片用）：PENDING 单带 expiresAt 渲染倒计时，
     * EXECUTED 单带 bankRef/executedAt 支撑「查看电子回单」入口。
     */
    public record OrderView(String confirmId, String fromAccount, String toAccount,
                            String toOwner, BigDecimal amount, String reason,
                            String status, String bankRef, Long executedAt, Long expiresAt) {

    }

    /** 最近收款人视图（queryRecentPayees 工具用） */
    public record PayeeView(String toAccount, String toOwner) {

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
        RecipientLookup to = findRecipient(toAccountOrOwner);
        if (to.denyReason() != null) {
            audit.record(memoryId, "transfer", "收款方歧义: " + toAccountOrOwner, "DENY");
            return TransferResult.deny(to.denyReason());
        }
        if (to.account().isEmpty()) {
            return TransferResult.fail("未找到收款账户「%s」".formatted(toAccountOrOwner));
        }
        if (from.get().accountNo().equals(to.account().get().accountNo())) {
            return TransferResult.deny("不能向本人账户转账");
        }

        RiskDecision decision = TransferRiskRules.check(
                TransferRiskRules.RiskLimits.forIdentity(identity, riskProps),
                to.account().get().accountNo(), amount, from.get().balance(),
                transferredToday(from.get().accountNo()));
        if (!decision.allowed()) {
            audit.record(memoryId, "transfer",
                    "预检拒绝: to=%s, amount=%s".formatted(to.account().get().accountNo(), amount), "DENY");
            return TransferResult.deny("转账被风控拦截：" + decision.reason());
        }

        String confirmId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO bank_transfer_order (confirm_id, memory_id, from_account, to_account, amount, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                """, confirmId, memoryId, from.get().accountNo(), to.account().get().accountNo(), amount,
                reason == null || reason.isBlank() ? null : reason);
        audit.record(memoryId, "transfer",
                "创建确认单 %s: to=%s, amount=%s".formatted(confirmId, to.account().get().accountNo(), amount), "PENDING");
        return TransferResult.pending(
                "转账确认单已创建（确认码 %s）：%s（%s）→ %s（%s），金额 %.2f 元，等待用户确认。"
                        .formatted(confirmId, from.get().accountNo(), from.get().owner(),
                                to.account().get().accountNo(), to.account().get().owner(), amount));
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
     * 恢复卡片的数据源：本会话未过期的 PENDING 单 + 最近 5 笔 EXECUTED 单。
     *
     * 卡片原本只随 SSE 流推一次，页面刷新/切换会话/流被中止后就「断线失联」——
     * 待确认的卡片丢了点不了，已确认的卡片丢了连「查看电子回单」入口都没了，
     * 用户拿着模型"转账成功"的回复找不到凭证。前端加载会话时调
     * GET /api/transfer/orders 用本方法重建两种卡片。PENDING 过期单与 confirm
     * 的过期判定同一口径（不返回死卡）；已取消/被拒的单没有可恢复的交互，不返回。
     */
    public List<OrderView> listOrders(String memoryId) {
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - confirmTtlMinutes * 60000);
        List<OrderView> orders = new ArrayList<>(jdbc.query("""
                SELECT confirm_id, from_account, to_account, amount, reason, created_at
                FROM bank_transfer_order
                WHERE memory_id = ? AND status = 'PENDING' AND created_at > ?
                ORDER BY id ASC LIMIT 10
                """, (rs, i) -> new OrderView(
                        rs.getString("confirm_id"),
                        rs.getString("from_account"),
                        rs.getString("to_account"),
                        ownerOf(rs.getString("to_account")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        "PENDING", null, null,
                        rs.getTimestamp("created_at").getTime() + confirmTtlMinutes * 60000),
                memoryId, cutoff));
        orders.addAll(jdbc.query("""
                SELECT confirm_id, from_account, to_account, amount, reason, bank_ref, executed_at
                FROM bank_transfer_order
                WHERE memory_id = ? AND status = 'EXECUTED'
                ORDER BY id DESC LIMIT 5
                """, (rs, i) -> new OrderView(
                        rs.getString("confirm_id"),
                        rs.getString("from_account"),
                        rs.getString("to_account"),
                        ownerOf(rs.getString("to_account")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        "EXECUTED",
                        rs.getString("bank_ref"),
                        rs.getTimestamp("executed_at") == null ? null : rs.getTimestamp("executed_at").getTime(),
                        null),
                memoryId));
        return orders;
    }

    /**
     * 供 queryRecentPayees 工具：本会话最近转账成功的收款人（按最近成功时间排序，至多 5 个）。
     * 有了这份名单，用户说「给李四转点」时模型能主动对上具体收款人；
     * 只统计 EXECUTED——取消/被拒的收款方不一定是用户真实想转的对象。
     * 与回单查询一样限本会话：会话记忆隔离是既有设计，跨会话历史是另一个话题。
     */
    public String describeRecentPayees(String memoryId) {
        BankIdentity identity = BankIdentity.fromMemoryId(memoryId);
        if (!identity.canTransfer()) {
            return "内部员工账号没有资金操作权限，无法查看转账收款人。";
        }
        List<PayeeView> payees = jdbc.query("""
                SELECT to_account
                FROM bank_transfer_order
                WHERE memory_id = ? AND status = 'EXECUTED'
                GROUP BY to_account
                ORDER BY MAX(id) DESC
                LIMIT 5
                """, (rs, i) -> new PayeeView(rs.getString("to_account"),
                        ownerOf(rs.getString("to_account"))),
                memoryId);
        audit.record(memoryId, "queryRecentPayees", "命中 " + payees.size() + " 个收款人", "SUCCESS");
        if (payees.isEmpty()) {
            return "本会话还没有转账成功的记录，暂无最近收款人。";
        }
        StringBuilder text = new StringBuilder("最近收款人：");
        for (PayeeView payee : payees) {
            text.append("\n- ").append(payee.toOwner()).append("（").append(payee.toAccount()).append("）");
        }
        return text.toString();
    }

    /**
     * 供 queryTransferLimit 工具：当前身份的限额档位、今日已用与剩余可转额度。
     * 与风控规则共用同一份 RiskLimits——「限额是多少」的回答口径必须和「能不能转」
     * 的判定口径一致，否则模型会拿一套数字、规则执行另一套。
     */
    public String describeLimit(String memoryId) {
        BankIdentity identity = BankIdentity.fromMemoryId(memoryId);
        if (!identity.canTransfer()) {
            return "内部员工账号没有资金操作权限。";
        }
        TransferRiskRules.RiskLimits limits =
                TransferRiskRules.RiskLimits.forIdentity(identity, riskProps);
        BigDecimal today = transferredToday(identity.boundAccount());
        BigDecimal remaining = limits.maxDaily().subtract(today).max(BigDecimal.ZERO);
        audit.record(memoryId, "queryTransferLimit", "identity=" + identity.role(), "SUCCESS");
        return "%s转账限额：单笔最高 %.2f 元，单日累计最高 %.2f 元；今日已转 %.2f 元，剩余可转 %.2f 元。"
                .formatted(identity.role(), limits.maxSingle(), limits.maxDaily(), today, remaining);
    }

    /** 支付密码校验结果：passed=false 时 message 直接面向用户（前端原样展示） */
    public record PasswordVerification(boolean passed, String message) {

    }

    /**
     * 用户确认路径的支付密码校验（管理端审批不走这里——内部操作不需要第二因子）。
     *
     * 试错锁定：同一确认单连续错 MAX_PWD_ATTEMPTS 次即作废（CAS 推到 REJECTED）。
     * 不锁的话，6 位数字密码最坏一百万次尝试总能命中，二次验证形同虚设。
     * 计数递增、作废与状态判断在同一个 READ_COMMITTED 事务里，并发输错不会漏计；
     * 校验通过即清零历史计数——「输对一次」就该重新起算，避免历史误伤。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PasswordVerification verifyPayPassword(String memoryId, String confirmId, String given) {
        String attempt = given == null ? "" : given.trim();
        if (attempt.isEmpty()) {
            return new PasswordVerification(false, "请输入支付密码后再确认转账。");
        }
        Optional<OrderRow> found = jdbc.query("""
                SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
                FROM bank_transfer_order WHERE confirm_id = ? AND memory_id = ?
                """, rowMapper(), confirmId, memoryId).stream().findFirst();
        if (found.isEmpty()) {
            // 与 confirmOrder 同一口径：不区分「不存在」与「不是你的」，不向试探者泄露信息
            return new PasswordVerification(false, "确认单不存在或已处理。");
        }
        OrderRow order = found.get();
        if (!"PENDING".equals(order.status())) {
            return new PasswordVerification(false, "该确认单已处理过，无需再次确认。");
        }
        if (payPassword.equals(attempt)) {
            jdbc.update("UPDATE bank_transfer_order SET pwd_fail_count = 0 WHERE id = ?", order.id());
            return new PasswordVerification(true, "支付密码校验通过。");
        }
        jdbc.update("UPDATE bank_transfer_order SET pwd_fail_count = pwd_fail_count + 1 WHERE id = ?",
                order.id());
        int failCount = jdbc.queryForObject(
                "SELECT pwd_fail_count FROM bank_transfer_order WHERE id = ?", Integer.class, order.id());
        if (failCount >= MAX_PWD_ATTEMPTS) {
            // CAS 作废：只有还挂在 PENDING 上的单子会被推到 REJECTED。
            // 若作废失败说明恰好被并发确认了——下次 confirmOrder 的幂等检查会兜住
            int voided = jdbc.update(
                    "UPDATE bank_transfer_order SET status = 'REJECTED' WHERE id = ? AND status = 'PENDING'",
                    order.id());
            if (voided > 0) {
                audit.record(memoryId, "transfer.confirm",
                        "支付密码连续错误 %d 次，确认单 %s 已作废".formatted(MAX_PWD_ATTEMPTS, confirmId), "DENY");
                return new PasswordVerification(false, "支付密码错误次数过多，确认单已作废。请重新发起转账。");
            }
        }
        audit.record(memoryId, "transfer.confirm",
                "支付密码错误（%d/%d）确认单 %s".formatted(failCount, MAX_PWD_ATTEMPTS, confirmId), "DENY");
        return new PasswordVerification(false,
                "支付密码错误，还可尝试 %d 次。".formatted(Math.max(0, MAX_PWD_ATTEMPTS - failCount)));
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
                SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
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

        // 幂等核心：状态机 CAS。并发/重复点击下只有一个请求能把 PENDING 推进到 EXECUTED。
        // 回单号与状态在同一条 UPDATE 里生成：回单是资金变动的凭证，状态没到 EXECUTED
        // 就绝不能存在 bank_ref，否则会出现「单子还没执行却查得到回单」的假凭证。
        int claimed = jdbc.update(
                "UPDATE bank_transfer_order SET status = 'EXECUTED', bank_ref = ? WHERE id = ? AND status = 'PENDING'",
                newBankRef(), order.id());
        if (claimed == 0) {
            audit.record(memoryId, "transfer.confirm", "重复提交确认单 " + confirmId, "IDEMPOTENT_SKIP");
            // 幂等消息按实际终态区分：把「密码试错作废」说成「已执行过」会误导用户
            return switch (order.status()) {
                case "REJECTED" -> TransferResult.deny(
                        "该确认单已作废（支付密码错误次数过多或已过期），资金未变动，请重新发起转账。");
                case "CANCELLED" -> TransferResult.pending("该确认单已取消，资金未变动。");
                case "EXECUTED" -> TransferResult.pending("该确认单已执行过，本次重复操作被幂等拦截，资金未变动。");
                default -> TransferResult.pending("该确认单已处理过（状态 %s），资金未变动。".formatted(order.status()));
            };
        }

        // 规则复检：卡片展示期间余额/日累计可能已被其他交易改变（限额按会话身份分档）
        RiskDecision decision = TransferRiskRules.check(
                TransferRiskRules.RiskLimits.forIdentity(identity, riskProps),
                order.toAccount(), order.amount(),
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
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
                        FROM bank_transfer_order WHERE confirm_id = ? AND memory_id = ?
                        """, rowMapper(), confirmId.trim(), memoryId)
                : jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
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
            case "EXECUTED" -> summary + "（已执行成功，资金已划转，银行回单号 "
                    + order.bankRef() + "，用户要凭证时引导其点确认卡片上的「查看电子回单」）";
            case "CANCELLED" -> summary + "（已取消，需要时请重新发起转账）";
            case "REJECTED" -> summary + "（已被拒绝或风控拦截，请重新发起）";
            default -> summary;
        };
    }

    /**
     * 供 queryReceipt 工具：电子回单查询。
     * 传了确认码查单笔回单详情；不传则列出本会话最近已执行的回单。
     * 只在 EXECUTED 且已生成回单号的单子上返回——回单是资金凭证，未执行的单据不存在回单。
     */
    public String describeReceipts(String memoryId, String confirmId) {
        boolean byId = confirmId != null && !confirmId.isBlank()
                && !"null".equalsIgnoreCase(confirmId.trim()) && !"-".equals(confirmId.trim());
        List<OrderRow> rows = byId
                ? jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
                        FROM bank_transfer_order
                        WHERE confirm_id = ? AND memory_id = ? AND status = 'EXECUTED' AND bank_ref IS NOT NULL
                        """, rowMapper(), confirmId.trim(), memoryId)
                : jdbc.query("""
                        SELECT id, confirm_id, from_account, to_account, amount, reason, status, bank_ref, created_at, executed_at
                        FROM bank_transfer_order
                        WHERE memory_id = ? AND status = 'EXECUTED' AND bank_ref IS NOT NULL
                        ORDER BY id DESC LIMIT 5
                        """, rowMapper(), memoryId);
        if (rows.isEmpty()) {
            return byId
                    ? "没有找到该确认单对应的电子回单（可能尚未执行成功或确认码有误）。"
                    : "本会话还没有已执行的转账，因此暂无电子回单。";
        }
        audit.record(memoryId, "queryReceipt",
                (byId ? "查询回单 " + confirmId : "查询最近回单列表") + "，命中 " + rows.size() + " 笔", "SUCCESS");
        StringBuilder text = new StringBuilder(byId ? "电子回单：" : "最近的电子回单：\n");
        for (OrderRow order : rows) {
            text.append("\n回单号 %s：%s → %s，金额 %.2f 元%s，交易时间 %tF %<tR。"
                    .formatted(order.bankRef(), order.fromAccount(), order.toAccount(), order.amount(),
                            note(order.reason), order.executedAt()));
        }
        return text + "\n（用户要查看凭证样式时，请引导其点击确认卡片上的「查看电子回单」按钮）";
    }

    /** 电子回单视图（前端弹窗渲染用）；仅 EXECUTED 且已生成回单号的单子存在 */
    public record ReceiptView(String confirmId, String bankRef, String fromAccount, String fromOwner,
                              String toAccount, String toOwner, BigDecimal amount, String reason,
                              String executedAt) {

    }

    /** 查询本会话某张确认单的电子回单；不存在 / 未执行时返回 empty（控制器转 404） */
    public Optional<ReceiptView> receipt(String memoryId, String confirmId) {
        if (confirmId == null || confirmId.isBlank()) {
            return Optional.empty();
        }
        List<ReceiptView> rows = jdbc.query("""
                SELECT confirm_id, bank_ref, from_account, to_account, amount, reason, executed_at
                FROM bank_transfer_order
                WHERE confirm_id = ? AND memory_id = ? AND status = 'EXECUTED' AND bank_ref IS NOT NULL
                """, (rs, i) -> new ReceiptView(
                        rs.getString("confirm_id"),
                        rs.getString("bank_ref"),
                        rs.getString("from_account"),
                        ownerOf(rs.getString("from_account")),
                        rs.getString("to_account"),
                        ownerOf(rs.getString("to_account")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getTimestamp("executed_at").toLocalDateTime().format(RECEIPT_TIME)),
                confirmId.trim(), memoryId);
        return rows.stream().findFirst();
    }

    /** 回单号：EB + 时间戳 + 8 位随机十六进制，长度与真实柜面流水号量级相当 */
    private String newBankRef() {
        String ts = java.time.LocalDateTime.now().format(BANK_REF_TIME);
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "EB" + ts + rand;
    }

    private static final DateTimeFormatter BANK_REF_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final DateTimeFormatter RECEIPT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private org.springframework.jdbc.core.RowMapper<OrderRow> rowMapper() {
        return (rs, i) -> new OrderRow(
                rs.getLong("id"),
                rs.getString("confirm_id"),
                rs.getString("from_account"),
                rs.getString("to_account"),
                rs.getBigDecimal("amount"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getString("bank_ref"),
                rs.getTimestamp("created_at"),
                rs.getTimestamp("executed_at"));
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

    /** 收款户名展示；账户被删除时给占位而不是让查询炸 500 */
    private String ownerOf(String accountNo) {
        List<String> owners = jdbc.query(
                "SELECT owner FROM bank_account WHERE account_no = ?",
                (rs, i) -> rs.getString(1), accountNo);
        return owners.isEmpty() ? "（账户已注销）" : owners.get(0);
    }

    private String note(String reason) {
        return reason == null || reason.isBlank() ? "" : "，附言「%s」".formatted(reason);
    }

    /** 只取账户本身（不查明细），账号精确匹配 */
    private Optional<Account> findAccount(String accountNo) {
        List<Account> found = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE account_no = ?",
                (rs, i) -> new Account(rs.getString("account_no"), rs.getString("owner"),
                        rs.getBigDecimal("balance"), List.of()),
                accountNo);
        return found.stream().findFirst();
    }

    /** 收款方解析结果：account 命中的账户；denyReason 非空表示同名歧义，需用户提供账号 */
    private record RecipientLookup(Optional<Account> account, String denyReason) {

        static RecipientLookup found(Account account) {
            return new RecipientLookup(Optional.of(account), null);
        }

        static RecipientLookup ambiguous(String denyReason) {
            return new RecipientLookup(Optional.empty(), denyReason);
        }

        static RecipientLookup notFound() {
            return new RecipientLookup(Optional.empty(), null);
        }
    }

    /**
     * 解析收款方：账号精确匹配优先；按户名命中多个账户时拒绝。
     * 「给李四转 200」在库里出现第二个李四后，绝不能靠 OR ... LIMIT 1 碰运气——
     * 命中的是谁取决于存储顺序，转错人就是资金事故。必须让用户提供账号。
     */
    private RecipientLookup findRecipient(String accountNoOrOwner) {
        List<Account> byNo = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE account_no = ?",
                (rs, i) -> new Account(rs.getString("account_no"), rs.getString("owner"),
                        rs.getBigDecimal("balance"), List.of()),
                accountNoOrOwner);
        if (!byNo.isEmpty()) {
            return RecipientLookup.found(byNo.get(0));
        }
        List<Account> byOwner = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE owner = ? ORDER BY account_no",
                (rs, i) -> new Account(rs.getString("account_no"), rs.getString("owner"),
                        rs.getBigDecimal("balance"), List.of()),
                accountNoOrOwner);
        if (byOwner.size() > 1) {
            return RecipientLookup.ambiguous(
                    "找到 %d 个名为「%s」的客户，为避免转错人，请提供完整收款账号后再转账"
                            .formatted(byOwner.size(), accountNoOrOwner));
        }
        return byOwner.isEmpty() ? RecipientLookup.notFound() : RecipientLookup.found(byOwner.get(0));
    }

    /** bankRef / executedAt 仅 EXECUTED 单非空（回单号与状态机 CAS 同一条 UPDATE 写入） */
    private record OrderRow(Long id, String confirmId, String fromAccount, String toAccount,
                            BigDecimal amount, String reason, String status, String bankRef,
                            Timestamp createdAt, Timestamp executedAt) {

    }
}
