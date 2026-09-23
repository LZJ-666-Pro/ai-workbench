package com.ai.workbench.bank.service;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 银行核心：数据落在 MySQL（bank_account / bank_transaction），JdbcTemplate 直查。
 * 对外方法签名与原 MockBankService 一致，AccountTools 无感知。
 */
@Service
public class DbBankService {

    private final JdbcTemplate jdbc;

    public DbBankService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按账号或户名精确匹配，优先账号 */
    public Optional<Account> find(String accountNoOrOwner) {
        List<Account> found = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account WHERE account_no = ? OR owner = ? LIMIT 1",
                (rs, i) -> new Account(
                        rs.getString("account_no"),
                        rs.getString("owner"),
                        rs.getBigDecimal("balance"),
                        recentTransactions(rs.getString("account_no"))),
                accountNoOrOwner, accountNoOrOwner);
        return found.stream().findFirst();
    }

    public String allAccounts() {
        List<String> items = jdbc.query(
                "SELECT account_no, owner, balance FROM bank_account ORDER BY account_no",
                (rs, i) -> "%s（%s）余额 %.2f 元".formatted(
                        rs.getString("account_no"),
                        describeOwner(rs.getString("account_no"), rs.getString("owner")),
                        rs.getBigDecimal("balance")));
        return String.join("；", items);
    }

    /** 全行概况：内部员工工具（bankOverview）的数据源 */
    public String overview() {
        return jdbc.queryForObject("""
                SELECT accounts, personal, corporate, owners, total FROM (
                    SELECT COUNT(*)                       AS accounts,
                           SUM(account_no NOT LIKE '8%')  AS personal,
                           SUM(account_no LIKE '8%')      AS corporate,
                           COUNT(DISTINCT owner)          AS owners,
                           COALESCE(SUM(balance), 0)      AS total
                    FROM bank_account) t
                """,
                (rs, i) -> "全行账户 %d 个（个人 %d / 对公 %d），客户 %d 名，总余额 %.2f 元".formatted(
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getBigDecimal(5).doubleValue()));
    }

    /** 账号段位约定：8 开头为对公账户，其余为个人账户 */
    public static String describeOwner(String accountNo, String owner) {
        return accountNo.startsWith("8") ? owner + "·对公" : owner + "·个人";
    }

    private List<String> recentTransactions(String accountNo) {
        return jdbc.queryForList(
                "SELECT description FROM bank_transaction WHERE account_no = ? ORDER BY id DESC LIMIT 5",
                String.class, accountNo);
    }
}
