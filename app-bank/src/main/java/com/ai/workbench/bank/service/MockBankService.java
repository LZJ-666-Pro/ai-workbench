package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * 模拟银行核心：内存造数，Phase 1 迁移到 MySQL。
 */
@Service
public class MockBankService {

    private final Map<String, Account> accounts = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        save(new Account("62220001", "张三", new BigDecimal("12850.50"), List.of(
                "2026-09-01 工资入账 +18000.00",
                "2026-09-05 房贷扣款 -6500.00",
                "2026-09-12 信用卡还款 -3200.00")));
        save(new Account("62220002", "李四", new BigDecimal("3420.00"), List.of(
                "2026-09-03 转账存入 +500.00",
                "2026-09-10 超市消费 -286.50")));
        save(new Account("62220003", "王五", new BigDecimal("98760.00"), List.of(
                "2026-08-28 理财赎回 +50000.00",
                "2026-09-15 水电缴费 -358.20")));
    }

    private void save(Account account) {
        accounts.put(account.accountNo(), account);
    }

    public Optional<Account> find(String accountNoOrOwner) {
        return accounts.values().stream()
                .filter(a -> a.accountNo().equals(accountNoOrOwner) || a.owner().equals(accountNoOrOwner))
                .findFirst();
    }

    public String allAccounts() {
        return accounts.values().stream()
                .map(a -> a.accountNo() + "(" + a.owner() + ")")
                .collect(Collectors.joining("、"));
    }
}
