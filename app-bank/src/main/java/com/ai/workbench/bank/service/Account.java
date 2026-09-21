package com.ai.workbench.bank.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 银行账户读模型：余额来自 bank_account（DECIMAL(12,2)），交易明细来自 bank_transaction。
 */
public record Account(String accountNo, String owner, BigDecimal balance, List<String> recentTransactions) {
}
