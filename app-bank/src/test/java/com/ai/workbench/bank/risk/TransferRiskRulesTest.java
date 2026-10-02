package com.ai.workbench.bank.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.ai.workbench.bank.identity.BankIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 风控硬规则的纯函数单测。
 *
 * 这是整个系统里唯一决定「钱能不能动」的确定性代码，也是最能被 LLM 影响的边界，
 * 因此覆盖到每个分支与每个限额的临界值（恰好等于限额必须放行，超出一分必须拒绝）。
 */
class TransferRiskRulesTest {

    private static final String PAYEE = "62220099";
    private static final String BLACKLISTED = "62220004";

    /** 余额充裕、当日未转过：把注意力集中在被断言的那条规则上 */
    private static RiskDecision check(BankIdentity identity, String to, String amount, String today) {
        return TransferRiskRules.check(identity, to, new BigDecimal(amount),
                new BigDecimal("1000000.00"), new BigDecimal(today));
    }

    private static RiskDecision checkRetail(String to, String amount, String today) {
        return check(BankIdentity.RETAIL, to, amount, today);
    }

    @Nested
    @DisplayName("金额基本校验")
    class AmountValidation {

        @ParameterizedTest(name = "金额 {0} 应被拒绝")
        @ValueSource(strings = {"0", "-1", "-0.01"})
        void rejectsNonPositiveAmount(String amount) {
            RiskDecision decision = checkRetail(PAYEE, amount, "0");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("必须大于 0");
        }

        @Test
        @DisplayName("超过两位小数被拒绝（防止精度悄悄被截断）")
        void rejectsMoreThanTwoDecimals() {
            RiskDecision decision = checkRetail(PAYEE, "100.001", "0");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("两位小数");
        }

        @Test
        @DisplayName("null 金额不抛异常，按拒绝处理")
        void rejectsNullAmount() {
            RiskDecision decision = TransferRiskRules.check(
                    BankIdentity.RETAIL, PAYEE, null, new BigDecimal("1000.00"), BigDecimal.ZERO);
            assertThat(decision.allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("黑名单")
    class Blacklist {

        @Test
        @DisplayName("命中黑名单直接拒绝，优先于金额判断")
        void rejectsBlacklistedPayee() {
            RiskDecision decision = checkRetail(BLACKLISTED, "100.00", "0");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("风控名单");
        }

        @Test
        @DisplayName("黑名单校验在限额之前：超额 + 黑名单也报风控名单")
        void blacklistTakesPrecedenceOverLimit() {
            RiskDecision decision = checkRetail(BLACKLISTED, "999999.00", "0");
            assertThat(decision.reason()).contains("风控名单");
        }
    }

    @Nested
    @DisplayName("个人客户限额（单笔 5000 / 单日 10000）")
    class RetailLimits {

        @Test
        @DisplayName("恰好等于单笔限额：放行")
        void allowsExactlySingleLimit() {
            assertThat(checkRetail(PAYEE, "5000.00", "0").allowed()).isTrue();
        }

        @Test
        @DisplayName("超出单笔限额一分：拒绝")
        void rejectsJustOverSingleLimit() {
            RiskDecision decision = checkRetail(PAYEE, "5000.01", "0");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("单笔");
        }

        @Test
        @DisplayName("当日累计恰好触顶：放行")
        void allowsExactlyDailyLimit() {
            // 5000 + 5000 == 10000，未超出
            assertThat(checkRetail(PAYEE, "5000.00", "5000.00").allowed()).isTrue();
        }

        @Test
        @DisplayName("当日累计超出限额：拒绝，并回显已用金额")
        void rejectsOverDailyLimit() {
            RiskDecision decision = checkRetail(PAYEE, "2000.00", "9000.00");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("当日累计").contains("9000.00");
        }
    }

    @Nested
    @DisplayName("对公客户限额档位（单笔 500000 / 单日 2000000）")
    class CorporateLimits {

        @Test
        @DisplayName("超过个人上限但在对公上限内：放行（档位确实分开了）")
        void corporateHasHigherSingleLimit() {
            RiskDecision decision = check(BankIdentity.CORPORATE, PAYEE, "50000.00", "0");
            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("超出对公单笔上限：拒绝")
        void rejectsOverCorporateSingleLimit() {
            RiskDecision decision = check(BankIdentity.CORPORATE, PAYEE, "500000.01", "0");
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("单笔");
        }

        @Test
        @DisplayName("对公日累计上限高于个人：个人会被拒的金额对公放行")
        void corporateDailyLimitIsHigher() {
            assertThat(check(BankIdentity.CORPORATE, PAYEE, "500000.00", "1000000.00").allowed()).isTrue();
            assertThat(checkRetail(PAYEE, "500000.00", "0").allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("余额校验")
    class Balance {

        @Test
        @DisplayName("余额恰好等于转账金额：放行（可清零）")
        void allowsExactlyBalance() {
            RiskDecision decision = TransferRiskRules.check(
                    BankIdentity.RETAIL, PAYEE, new BigDecimal("300.00"),
                    new BigDecimal("300.00"), BigDecimal.ZERO);
            assertThat(decision.allowed()).isTrue();
        }

        @Test
        @DisplayName("余额不足一分：拒绝，并回显当前余额")
        void rejectsInsufficientBalance() {
            RiskDecision decision = TransferRiskRules.check(
                    BankIdentity.RETAIL, PAYEE, new BigDecimal("300.01"),
                    new BigDecimal("300.00"), BigDecimal.ZERO);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("余额不足");
        }

        @Test
        @DisplayName("规则是纯函数：同参数多次调用结果一致，不受调用顺序影响")
        void isDeterministic() {
            RiskDecision first = checkRetail(BLACKLISTED, "100.00", "0");
            RiskDecision second = checkRetail(PAYEE, "100.00", "0");
            RiskDecision third = checkRetail(BLACKLISTED, "100.00", "0");
            assertThat(first.allowed()).isFalse();
            assertThat(second.allowed()).isTrue();
            assertThat(third.reason()).isEqualTo(first.reason());
        }
    }
}
