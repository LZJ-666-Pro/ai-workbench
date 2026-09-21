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
                        rs.getString("account_no"), rs.getString("owner"), rs.getBigDecimal("balance")));
        return String.join("；", items);
    }

    private List<String> recentTransactions(String accountNo) {
        return jdbc.queryForList(
                "SELECT description FROM bank_transaction WHERE account_no = ? ORDER BY id DESC LIMIT 5",
                String.class, accountNo);
    }
}
