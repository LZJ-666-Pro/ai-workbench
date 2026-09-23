package com.ai.workbench.bank.risk;

import java.math.BigDecimal;
import java.util.Set;

import com.ai.workbench.bank.identity.BankIdentity;

/**
 * 转账硬规则：纯函数、无 IO，所需数据由调用方（TransferService）查询后传入。
 *
 * 设计要点：确定性约束在代码层执行，不经过 LLM——模型只负责从自然语言里抽参数，
 * 能不能转、转多少由这里的规则说了算。新增规则 = 加一个 if，可单测、可审计。
 *
 * 限额按服务对象分档：对公客户单笔/单日限额高于个人。
 */
public final class TransferRiskRules {

    /** 个人客户单笔限额（元） */
    public static final BigDecimal MAX_SINGLE = new BigDecimal("5000.00");

    /** 个人客户单日累计限额（元），只统计已执行的转账订单 */
    public static final BigDecimal MAX_DAILY = new BigDecimal("10000.00");

    /** 对公客户单笔限额（元） */
    public static final BigDecimal MAX_SINGLE_CORPORATE = new BigDecimal("500000.00");

    /** 对公客户单日累计限额（元） */
    public static final BigDecimal MAX_DAILY_CORPORATE = new BigDecimal("2000000.00");

    /** 收款黑名单（命中直接拒绝） */
    public static final Set<String> BLACKLIST = Set.of("62220004");

    private TransferRiskRules() {
    }

    public static RiskDecision check(String toAccountNo, BigDecimal amount,
                                     BigDecimal balance, BigDecimal todayTransferred) {
        return check(BankIdentity.RETAIL, toAccountNo, amount, balance, todayTransferred);
    }

    public static RiskDecision check(BankIdentity identity, String toAccountNo, BigDecimal amount,
                                     BigDecimal balance, BigDecimal todayTransferred) {
        BigDecimal maxSingle = identity == BankIdentity.CORPORATE ? MAX_SINGLE_CORPORATE : MAX_SINGLE;
        BigDecimal maxDaily = identity == BankIdentity.CORPORATE ? MAX_DAILY_CORPORATE : MAX_DAILY;
        if (amount == null || amount.signum() <= 0) {
            return RiskDecision.deny("转账金额必须大于 0");
        }
        if (amount.scale() > 2) {
            return RiskDecision.deny("转账金额最多支持两位小数");
        }
        if (BLACKLIST.contains(toAccountNo)) {
            return RiskDecision.deny("收款账户在风控名单中，禁止转账");
        }
        if (amount.compareTo(maxSingle) > 0) {
            return RiskDecision.deny("单笔转账不能超过 %.2f 元".formatted(maxSingle));
        }
        if (todayTransferred.add(amount).compareTo(maxDaily) > 0) {
            return RiskDecision.deny("当日累计转账不能超过 %.2f 元，今日已转 %.2f 元"
                    .formatted(maxDaily, todayTransferred));
        }
        if (balance.compareTo(amount) < 0) {
            return RiskDecision.deny("余额不足，当前余额 %.2f 元".formatted(balance));
        }
        return RiskDecision.allow();
    }
}