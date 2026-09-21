package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模拟银行账户。Phase 1 会迁移到 MySQL 域模型 + MyBatis/JPA。
 */
public record Account(String accountNo, String owner, BigDecimal balance, List<String> recentTransactions) {
}
