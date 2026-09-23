package com.ai.workbench.bank.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.ai.workbench.bank.admin.AdminDtos.AccountBrief;
import com.ai.workbench.bank.admin.AdminDtos.AccountView;
import com.ai.workbench.bank.admin.AdminDtos.ApprovalBoard;
import com.ai.workbench.bank.admin.AdminDtos.AuditLogView;
import com.ai.workbench.bank.admin.AdminDtos.BalancePoint;
import com.ai.workbench.bank.admin.AdminDtos.CustomerProfile;
import com.ai.workbench.bank.admin.AdminDtos.CustomerView;
import com.ai.workbench.bank.admin.AdminDtos.DailyFlowPoint;
import com.ai.workbench.bank.admin.AdminDtos.JourneyNode;
import com.ai.workbench.bank.admin.AdminDtos.LimitPackage;
import com.ai.workbench.bank.admin.AdminDtos.OrderJourney;
import com.ai.workbench.bank.admin.AdminDtos.OrderView;
import com.ai.workbench.bank.admin.AdminDtos.OverviewStats;
import com.ai.workbench.bank.admin.AdminDtos.PageResult;
import com.ai.workbench.bank.admin.AdminDtos.TxnView;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.risk.TransferRiskRules;

/**
 * 管理端只读查询：JdbcTemplate 直查，风格与 DbBankService 一致。
 * 全部为 SELECT，不做任何写操作；筛选条件动态拼 WHERE 但值一律走 ? 占位。
 */
@Service
public class BankAdminQueryService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;

    public BankAdminQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 概览统计：沿用 DbBankService.overview 的聚合口径，另加今日流水 */
    public OverviewStats overview() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance), 0)          AS total,
                       COUNT(*)                           AS accounts,
                       COALESCE(SUM(account_no NOT LIKE '8%'), 0) AS personal,
                       COALESCE(SUM(account_no LIKE '8%'), 0)     AS corporate,
                       COUNT(DISTINCT owner)              AS customers
                FROM bank_account
                """,
                (rs, i) -> {
                    BigDecimal total = rs.getBigDecimal("total");
                    long accounts = rs.getLong("accounts");
                    long personal = rs.getLong("personal");
                    long corporate = rs.getLong("corporate");
                    long customers = rs.getLong("customers");
                    return jdbc.queryForObject("""
                            SELECT COUNT(*)                   AS cnt,
                                   COALESCE(SUM(amount), 0)   AS amt
                            FROM bank_transaction
                            WHERE DATE(created_at) = CURDATE()
                            """,
                            (rs2, j) -> new OverviewStats(
                                    total, accounts, personal, corporate, customers,
                                    rs2.getLong("cnt"), rs2.getBigDecimal("amt")));
                });
    }

    /** 账户分页：keyword 模糊匹配账号/户名，type 按段位约定（8 开头=对公） */
    public PageResult<AccountView> accounts(String keyword, String type, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (account_no LIKE ? OR owner LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if ("personal".equals(type)) {
            where.append(" AND account_no NOT LIKE '8%'");
        } else if ("corporate".equals(type)) {
            where.append(" AND account_no LIKE '8%'");
        }
        String w = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM bank_account" + w, Long.class, args.toArray());
        List<AccountView> list = jdbc.query("""
                SELECT a.account_no, a.owner, a.balance,
                       (SELECT COUNT(*) FROM bank_transaction t WHERE t.account_no = a.account_no) AS txn_count
                FROM bank_account a
                """ + w + " ORDER BY a.account_no LIMIT ? OFFSET ?",
                (rs, i) -> new AccountView(
                        rs.getString("account_no"),
                        rs.getString("owner"),
                        rs.getString("account_no").startsWith("8") ? "corporate" : "personal",
                        rs.getBigDecimal("balance"),
                        rs.getLong("txn_count")),
                appendPaging(args, page, size).toArray());
        return new PageResult<>(list, total, page, size);
    }

    /** 单账户流水分页 */
    public PageResult<TxnView> accountTransactions(String accountNo, int page, int size) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transaction WHERE account_no = ?", Long.class, accountNo);
        List<TxnView> list = jdbc.query("""
                SELECT t.id, t.account_no, a.owner, t.amount, t.description, t.created_at
                FROM bank_transaction t LEFT JOIN bank_account a ON a.account_no = t.account_no
                WHERE t.account_no = ? ORDER BY t.id DESC LIMIT ? OFFSET ?
                """,
                txnMapper(), accountNo, size, (page - 1) * size);
        return new PageResult<>(list, total, page, size);
    }

    /** 全行流水分页：accountNo 精确筛、direction 按金额符号筛 */
    public PageResult<TxnView> transactions(String accountNo, String direction, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (accountNo != null && !accountNo.isBlank()) {
            where.append(" AND t.account_no = ?");
            args.add(accountNo.trim());
        }
        if ("in".equals(direction)) {
            where.append(" AND t.amount > 0");
        } else if ("out".equals(direction)) {
            where.append(" AND t.amount < 0");
        }
        String join = """
                FROM bank_transaction t LEFT JOIN bank_account a ON a.account_no = t.account_no
                """;
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + join + where, Long.class, args.toArray());
        List<TxnView> list = jdbc.query(
                "SELECT t.id, t.account_no, a.owner, t.amount, t.description, t.created_at "
                        + join + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                txnMapper(), appendPaging(args, page, size).toArray());
        return new PageResult<>(list, total, page, size);
    }

    /** AI 转账订单分页，status 精确筛 */
    public PageResult<OrderView> transferOrders(String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status.trim());
        }
        String w = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM bank_transfer_order" + w, Long.class, args.toArray());
        List<OrderView> list = jdbc.query("""
                SELECT id, confirm_id, memory_id, from_account, to_account, amount, reason, status, created_at
                FROM bank_transfer_order
                """ + w + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new OrderView(
                        rs.getLong("id"),
                        rs.getString("confirm_id"),
                        rs.getString("memory_id"),
                        rs.getString("from_account"),
                        rs.getString("to_account"),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        formatTime(rs.getTimestamp("created_at"))),
                appendPaging(args, page, size).toArray());
        return new PageResult<>(list, total, page, size);
    }

    /** AI 工具审计日志分页 */
    public PageResult<AuditLogView> auditLogs(String result, String toolName, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (result != null && !result.isBlank()) {
            where.append(" AND result = ?");
            args.add(result.trim());
        }
        if (toolName != null && !toolName.isBlank()) {
            where.append(" AND tool_name = ?");
            args.add(toolName.trim());
        }
        String w = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM bank_audit_log" + w, Long.class, args.toArray());
        List<AuditLogView> list = jdbc.query("""
                SELECT id, memory_id, tool_name, detail, result, created_at
                FROM bank_audit_log
                """ + w + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AuditLogView(
                        rs.getLong("id"),
                        rs.getString("memory_id"),
                        rs.getString("tool_name"),
                        rs.getString("detail"),
                        rs.getString("result"),
                        formatTime(rs.getTimestamp("created_at"))),
                appendPaging(args, page, size).toArray());
        return new PageResult<>(list, total, page, size);
    }

    /** 饼图：账户余额分布 */
    public List<BalancePoint> balanceDistribution() {
        return jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account ORDER BY balance DESC",
                (rs, i) -> new BalancePoint(
                        rs.getString("account_no"),
                        rs.getString("owner"),
                        rs.getBigDecimal("balance")));
    }

    /** 折线：近 N 天每日入账/支出，无流水的日期补零保证 x 轴连续 */
    public List<DailyFlowPoint> dailyFlow(int days) {
        int n = switch (days) {
            case 30 -> 30;
            default -> 7;
        };
        LocalDate from = LocalDate.now().minusDays(n - 1L);
        List<DailyFlowPoint> rows = jdbc.query("""
                SELECT DATE(created_at) AS d,
                       COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0) AS income,
                       COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0) AS expense
                FROM bank_transaction
                WHERE DATE(created_at) >= ?
                GROUP BY DATE(created_at)
                """,
                (rs, i) -> {
                    LocalDate d = rs.getDate("d").toLocalDate();
                    return new DailyFlowPoint(d.toString(),
                            rs.getBigDecimal("income"), rs.getBigDecimal("expense"));
                },
                from);
        Map<LocalDate, DailyFlowPoint> byDate = new LinkedHashMap<>();
        for (DailyFlowPoint p : rows) {
            byDate.put(LocalDate.parse(p.date()), p);
        }
        List<DailyFlowPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate d = from.plusDays(i);
            out.add(byDate.getOrDefault(d,
                    new DailyFlowPoint(d.toString(), BigDecimal.ZERO, BigDecimal.ZERO)));
        }
        return out;
    }

    // ==================== 客户 360 视图 ====================

    /** 客户列表：按户名聚合名下账户（owner 在 bank_account 即视为客户） */
    public List<CustomerView> customers() {
        List<AccountBrief> rows = jdbc.query(
                "SELECT owner, account_no, balance FROM bank_account ORDER BY owner, account_no",
                (rs, i) -> new AccountBrief(rs.getString("owner"),
                        rs.getString("account_no"), rs.getBigDecimal("balance")));
        Map<String, List<AccountBrief>> byOwner = new LinkedHashMap<>();
        for (AccountBrief a : rows) {
            byOwner.computeIfAbsent(a.owner(), k -> new ArrayList<>()).add(a);
        }
        List<CustomerView> out = new ArrayList<>();
        for (var e : byOwner.entrySet()) {
            List<AccountBrief> accs = e.getValue();
            BigDecimal total = accs.stream().map(AccountBrief::balance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean hasPersonal = accs.stream().anyMatch(a -> !a.accountNo().startsWith("8"));
            boolean hasCorporate = accs.stream().anyMatch(a -> a.accountNo().startsWith("8"));
            String type = hasPersonal && hasCorporate ? "mixed" : hasCorporate ? "corporate" : "personal";
            out.add(new CustomerView(e.getKey(), accs.size(), total, type, accs));
        }
        return out;
    }

    /** 客户 360 全景：账户 + 收支统计 + 订单统计 + 审计数 + 各类近期轨迹 */
    public CustomerProfile customerProfile(String owner) {
        List<AccountBrief> accs = jdbc.query(
                "SELECT owner, account_no, balance FROM bank_account WHERE owner = ? ORDER BY account_no",
                (rs, i) -> new AccountBrief(rs.getString("owner"),
                        rs.getString("account_no"), rs.getBigDecimal("balance")),
                owner);
        if (accs.isEmpty()) {
            return null;
        }
        BigDecimal total = accs.stream().map(AccountBrief::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean corporate = accs.stream().allMatch(a -> a.accountNo().startsWith("8"));
        String inList = String.join(",", accs.stream().map(a -> "?" ).toList());
        Object[] accArgs = accs.stream().map(AccountBrief::accountNo).toArray();

        long txnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transaction WHERE account_no IN (" + inList + ")",
                Long.class, accArgs);
        BigDecimal income = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0) FROM bank_transaction WHERE account_no IN (" + inList + ")",
                BigDecimal.class, accArgs);
        BigDecimal expense = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0) FROM bank_transaction WHERE account_no IN (" + inList + ")",
                BigDecimal.class, accArgs);
        List<TxnView> recentTxns = jdbc.query(
                "SELECT t.id, t.account_no, a.owner, t.amount, t.description, t.created_at "
                        + "FROM bank_transaction t LEFT JOIN bank_account a ON a.account_no = t.account_no "
                        + "WHERE t.account_no IN (" + inList + ") ORDER BY t.id DESC LIMIT 10",
                txnMapper(), accArgs);

        // 订单：from/to 任一账号属于该客户即相关
        String accList2 = String.join(",", accs.stream().map(a -> "?").toList());
        long orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE from_account IN (" + accList2 + ") OR to_account IN (" + accList2 + ")",
                Long.class, concat(accArgs, accArgs));
        long orderExecuted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'EXECUTED' AND (from_account IN (" + accList2 + ") OR to_account IN (" + accList2 + "))",
                Long.class, concat(accArgs, accArgs));
        BigDecimal orderAmount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM bank_transfer_order WHERE status = 'EXECUTED' AND from_account IN (" + accList2 + ")",
                BigDecimal.class, accArgs);
        List<OrderView> recentOrders = jdbc.query(
                "SELECT id, confirm_id, memory_id, from_account, to_account, amount, reason, status, created_at "
                        + "FROM bank_transfer_order WHERE from_account IN (" + accList2 + ") OR to_account IN (" + accList2 + ") "
                        + "ORDER BY id DESC LIMIT 5",
                orderMapper(), concat(accArgs, accArgs));

        // 审计：本客户相关会话（相关订单的 memoryId）或 detail 提到该客户账号
        List<String> memoryIds = recentOrders.stream().map(OrderView::memoryId).distinct().toList();
        long auditCount = countAudits(owner, accArgs, memoryIds);
        List<AuditLogView> recentAudits = recentAudits(owner, accArgs, memoryIds);

        return new CustomerProfile(owner, corporate ? "corporate" : "personal", accs, total,
                txnCount, income, expense, orderCount, orderExecuted, orderAmount,
                auditCount, recentTxns, recentOrders, recentAudits);
    }

    private long countAudits(String owner, Object[] accArgs, List<String> memoryIds) {
        String memList = memoryIds.isEmpty() ? "''" : String.join(",",
                memoryIds.stream().map(m -> "?").toList());
        Object[] args = concat(new Object[]{owner}, accArgs, memoryIds.toArray());
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_audit_log WHERE detail LIKE ? OR memory_id IN (" + memList + ")",
                Long.class, args);
        return n == null ? 0 : n;
    }

    private List<AuditLogView> recentAudits(String owner, Object[] accArgs, List<String> memoryIds) {
        String memList = memoryIds.isEmpty() ? "''" : String.join(",",
                memoryIds.stream().map(m -> "?").toList());
        Object[] args = concat(new Object[]{owner}, accArgs, memoryIds.toArray());
        return jdbc.query(
                "SELECT id, memory_id, tool_name, detail, result, created_at FROM bank_audit_log "
                        + "WHERE detail LIKE ? OR memory_id IN (" + memList + ") "
                        + "ORDER BY id DESC LIMIT 10",
                auditMapper(), args);
    }

    // ==================== 审批中心 ====================

    /** 审批看板：状态统计 + 待审批队列（全量，PENDING 通常很少） */
    public ApprovalBoard approvalBoard() {
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'PENDING'", Long.class);
        Long executedToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'EXECUTED' AND DATE(created_at) = CURDATE()",
                Long.class);
        Long cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'CANCELLED'", Long.class);
        Long rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_transfer_order WHERE status = 'REJECTED'", Long.class);
        List<OrderView> pendingOrders = jdbc.query(
                "SELECT id, confirm_id, memory_id, from_account, to_account, amount, reason, status, created_at "
                        + "FROM bank_transfer_order WHERE status = 'PENDING' ORDER BY id DESC",
                orderMapper());
        return new ApprovalBoard(pending, executedToday, cancelled, rejected, pendingOrders);
    }

    /** 交易旅程：订单本体 + 时间线（创建事件 + 关联审计 + 执行流水） */
    public OrderJourney orderJourney(long orderId) {
        List<OrderView> found = jdbc.query(
                "SELECT id, confirm_id, memory_id, from_account, to_account, amount, reason, status, created_at "
                        + "FROM bank_transfer_order WHERE id = ?",
                orderMapper(), orderId);
        if (found.isEmpty()) {
            return null;
        }
        OrderView order = found.get(0);
        List<JourneyNode> timeline = new ArrayList<>();
        // 1) 创建事件：从审计日志找创建确认单的记录
        jdbc.query(
                "SELECT detail, result, created_at FROM bank_audit_log "
                        + "WHERE memory_id = ? AND detail LIKE ? ORDER BY id ASC LIMIT 5",
                (rs, i) -> {
                    timeline.add(new JourneyNode(formatTime(rs.getTimestamp("created_at")),
                            "创建确认单", rs.getString("detail"), rs.getString("result")));
                    return order;
                },
                order.memoryId(), "%" + order.confirmId() + "%");
        // 2) 会话内全部工具调用（按时间正序，完整呈现 AI 操作轨迹）
        jdbc.query(
                "SELECT tool_name, detail, result, created_at FROM bank_audit_log "
                        + "WHERE memory_id = ? ORDER BY id DESC LIMIT 15",
                (rs, i) -> {
                    timeline.add(new JourneyNode(formatTime(rs.getTimestamp("created_at")),
                            "AI 调用 " + rs.getString("tool_name"), rs.getString("detail"),
                            rs.getString("result")));
                    return order;
                },
                order.memoryId());
        // 3) 执行流水（EXECUTED 时 from 账号产生的支出流水）
        if ("EXECUTED".equals(order.status())) {
            jdbc.query(
                    "SELECT description, created_at FROM bank_transaction "
                            + "WHERE account_no = ? AND amount < 0 AND created_at >= ? "
                            + "ORDER BY id DESC LIMIT 3",
                    (rs, i) -> {
                        timeline.add(new JourneyNode(formatTime(rs.getTimestamp("created_at")),
                                "资金划转", rs.getString("description"), "SUCCESS"));
                        return order;
                    },
                    order.fromAccount(), order.createdAt());
        }
        timeline.sort(java.util.Comparator.comparing(JourneyNode::time));
        return new OrderJourney(order, timeline);
    }

    // ==================== 交易限额 ====================

    /** 限额包：从代码层风控规则读取当前生效配置（单点事实源，避免与 TransferRiskRules 漂移） */
    public List<LimitPackage> limitPackages() {
        List<LimitPackage> out = new ArrayList<>();
        for (BankIdentity identity : BankIdentity.values()) {
            boolean corporate = identity == BankIdentity.CORPORATE;
            out.add(new LimitPackage(
                    identity.id(),
                    identity.displayName(),
                    identity.role(),
                    identity.boundAccount() == null ? "—" : identity.boundAccount(),
                    identity.canTransfer(),
                    identity.isStaff() ? "无资金权限" : (corporate
                            ? TransferRiskRules.MAX_SINGLE_CORPORATE.toPlainString()
                            : TransferRiskRules.MAX_SINGLE.toPlainString()) + " 元",
                    identity.isStaff() ? "—" : (corporate
                            ? TransferRiskRules.MAX_DAILY_CORPORATE.toPlainString()
                            : TransferRiskRules.MAX_DAILY.toPlainString()) + " 元"));
        }
        return out;
    }

    private static org.springframework.jdbc.core.RowMapper<TxnView> txnMapper() {
        return (rs, i) -> new TxnView(
                rs.getLong("id"),
                rs.getString("account_no"),
                rs.getString("owner") == null ? "（已删账户）" : rs.getString("owner"),
                rs.getBigDecimal("amount"),
                rs.getString("description"),
                formatTime(rs.getTimestamp("created_at")));
    }

    private static org.springframework.jdbc.core.RowMapper<OrderView> orderMapper() {
        return (rs, i) -> new OrderView(
                rs.getLong("id"),
                rs.getString("confirm_id"),
                rs.getString("memory_id"),
                rs.getString("from_account"),
                rs.getString("to_account"),
                rs.getBigDecimal("amount"),
                rs.getString("reason"),
                rs.getString("status"),
                formatTime(rs.getTimestamp("created_at")));
    }

    private static org.springframework.jdbc.core.RowMapper<AuditLogView> auditMapper() {
        return (rs, i) -> new AuditLogView(
                rs.getLong("id"),
                rs.getString("memory_id"),
                rs.getString("tool_name"),
                rs.getString("detail"),
                rs.getString("result"),
                formatTime(rs.getTimestamp("created_at")));
    }

    private static Object[] concat(Object[]... arrays) {
        List<Object> all = new ArrayList<>();
        for (Object[] arr : arrays) {
            all.addAll(java.util.Arrays.asList(arr));
        }
        return all.toArray();
    }

    private static List<Object> appendPaging(List<Object> args, int page, int size) {
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) (page - 1) * size);
        return all;
    }

    private static String formatTime(Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
