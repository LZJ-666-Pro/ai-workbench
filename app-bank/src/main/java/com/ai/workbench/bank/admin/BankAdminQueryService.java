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

import com.ai.workbench.bank.admin.AdminDtos.AccountView;
import com.ai.workbench.bank.admin.AdminDtos.AuditLogView;
import com.ai.workbench.bank.admin.AdminDtos.BalancePoint;
import com.ai.workbench.bank.admin.AdminDtos.DailyFlowPoint;
import com.ai.workbench.bank.admin.AdminDtos.OrderView;
import com.ai.workbench.bank.admin.AdminDtos.OverviewStats;
import com.ai.workbench.bank.admin.AdminDtos.PageResult;
import com.ai.workbench.bank.admin.AdminDtos.TxnView;

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
                SELECT id, confirm_id, from_account, to_account, amount, reason, status, created_at
                FROM bank_transfer_order
                """ + w + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new OrderView(
                        rs.getLong("id"),
                        rs.getString("confirm_id"),
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

    private static List<Object> appendPaging(List<Object> args, int page, int size) {
        List<Object> all = new ArrayList<>(args);
        all.add(size);
        all.add((long) (page - 1) * size);
        return all;
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

    private static String formatTime(Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
