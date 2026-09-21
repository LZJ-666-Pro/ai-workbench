package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.ai.workbench.bank.risk.RiskDecision;
import com.ai.workbench.bank.risk.TransferRiskRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 转账执行服务：风控校验、余额变更、订单与流水落库在同一个事务里完成。
 *
 * 这是「AI 调用后台接口执行任务」的范本——LLM 只负责抽取参数，
 * 能否转账、怎么扣款、如何留痕全部由这里的代码决定。
 */
@Service
public class TransferService {

    /** 演示登录态固定为该账户；Phase 2 接入登录后改为从会话中取当前用户 */
    @Value("${bank.current-account-no:62220001}")
    private String currentAccountNo;

    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 结果三元组：SUCCESS 执行成功 / DENY 被规则拒绝 / FAIL 系统性失败。
     * message 面向最终用户（LLM 会原样转述），拒绝与失败都不抛异常、不重试。
     */
    public record TransferResult(String kind, String message) {

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

    @Transactional
    public TransferResult transfer(String toAccountOrOwner, BigDecimal amount, String reason) {
        Optional<Account> from = findAccount(currentAccountNo);
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

        BigDecimal todayTransferred = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM bank_transfer_order
                WHERE from_account = ? AND status = 'EXECUTED' AND created_at >= ?
                """, BigDecimal.class, from.get().accountNo(), Timestamp.valueOf(LocalDate.now().atStartOfDay()));

        RiskDecision decision = TransferRiskRules.check(
                to.get().accountNo(), amount, from.get().balance(), todayTransferred);
        if (!decision.allowed()) {
            return TransferResult.deny("转账被风控拦截：" + decision.reason());
        }

        // 乐观扣款：balance >= ? 条件保证并发下不会扣成负数，影响行数为 0 即余额不足
        int debited = jdbc.update(
                "UPDATE bank_account SET balance = balance - ? WHERE account_no = ? AND balance >= ?",
                amount, from.get().accountNo(), amount);
        if (debited == 0) {
            return TransferResult.deny("余额不足");
        }
        jdbc.update("UPDATE bank_account SET balance = balance + ? WHERE account_no = ?",
                amount, to.get().accountNo());

        jdbc.update("""
                INSERT INTO bank_transfer_order (from_account, to_account, amount, reason, status)
                VALUES (?, ?, ?, ?, 'EXECUTED')
                """, from.get().accountNo(), to.get().accountNo(), amount, note(reason));

        String note = note(reason);
        jdbc.update("INSERT INTO bank_transaction (account_no, amount, description) VALUES (?, ?, ?)",
                from.get().accountNo(), amount.negate(),
                "转账给 %s（%s）-%.2f 元%s".formatted(to.get().owner(), to.get().accountNo(), amount, note));
        jdbc.update("INSERT INTO bank_transaction (account_no, amount, description) VALUES (?, ?, ?)",
                to.get().accountNo(), amount,
                "收到 %s（%s）转账 +%.2f 元%s".formatted(from.get().owner(), from.get().accountNo(), amount, note));

        BigDecimal newBalance = jdbc.queryForObject(
                "SELECT balance FROM bank_account WHERE account_no = ?",
                BigDecimal.class, from.get().accountNo());
        return TransferResult.success("转账成功：%s（%s）→ %s（%s），金额 %.2f 元，付款方剩余余额 %.2f 元"
                .formatted(from.get().accountNo(), from.get().owner(),
                        to.get().accountNo(), to.get().owner(), amount, newBalance));
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
}
