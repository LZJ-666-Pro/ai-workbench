package com.ai.workbench.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.ai.workbench.core.config.LlmProperties;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 护栏的决策逻辑测试：两层拦截各自独立生效，且「关掉开关」能真正放行。
 * 用量存储用子类替身（不连库），把被测逻辑收窄到「怎么判」这一件事上。
 */
class LlmGuardTest {

    /** LlmUsageStore 替身：不连库，直接给出「今日已用」并记录写入 */
    static class FakeUsageStore extends LlmUsageStore {

        long usedToday;
        final List<String> written = new ArrayList<>();
        final List<String> queriedScopes = new ArrayList<>();

        FakeUsageStore() {
            super(null);
        }

        @Override
        public long tokensUsedToday(String scope) {
            queriedScopes.add(scope);
            return usedToday;
        }

        @Override
        public void record(String scope, String memoryId, String agent, String model,
                           int inputTokens, int outputTokens, String traceId) {
            written.add("%s|%s|%s|%s|%d|%d|%s".formatted(
                    scope, memoryId, agent, model, inputTokens, outputTokens, traceId));
        }
    }

    private static LlmGuard guard(LlmGuardProperties props, FakeUsageStore store) {
        LlmProperties llm = new LlmProperties();
        llm.setModelName("qwen3.8-flash");
        return new LlmGuard(props, store, llm);
    }

    private static LlmGuardProperties props(int perMinute, long dailyLimit, boolean enabled) {
        LlmGuardProperties p = new LlmGuardProperties();
        p.setRequestsPerMinute(perMinute);
        p.setDailyTokenLimit(dailyLimit);
        p.setEnabled(enabled);
        return p;
    }

    @Nested
    @DisplayName("计费主体解析")
    class ScopeDerivation {

        @Test
        @DisplayName("三段格式去掉会话段，得到服务对象维度")
        void dropsSessionSegment() {
            assertThat(LlmGuard.scopeOf("bank:zhangsan:7f3a-1")).isEqualTo("bank:zhangsan");
            assertThat(LlmGuard.scopeOf("bank:corp001:9e2b-2")).isEqualTo("bank:corp001");
        }

        @Test
        @DisplayName("两段旧格式没有身份段，整体归到应用维度")
        void legacyTwoSegmentFallsBackToApp() {
            assertThat(LlmGuard.scopeOf("bank:7f3a-1")).isEqualTo("bank");
        }

        @Test
        @DisplayName("无冒号原样返回；空值归到 \"-\"，与审计表缺省一致")
        void degenerateInputs() {
            assertThat(LlmGuard.scopeOf("bank")).isEqualTo("bank");
            assertThat(LlmGuard.scopeOf(null)).isEqualTo("-");
            assertThat(LlmGuard.scopeOf("  ")).isEqualTo("-");
        }
    }

    @Nested
    @DisplayName("限流层")
    class RateLimit {

        @Test
        @DisplayName("超出每分钟配额即拒绝，且提示里带上配额数字")
        void rejectsBeyondPerMinuteQuota() {
            FakeUsageStore store = new FakeUsageStore();
            LlmGuard guard = guard(props(2, 1_000_000, true), store);

            assertThat(guard.check("bank:zhangsan:s1").allowed()).isTrue();
            assertThat(guard.check("bank:zhangsan:s1").allowed()).isTrue();

            LlmGuard.Decision third = guard.check("bank:zhangsan:s1");
            assertThat(third.allowed()).isFalse();
            assertThat(third.reason()).isEqualTo(LlmGuard.REASON_RATE_LIMIT);
            assertThat(third.message()).contains("2");
        }

        @Test
        @DisplayName("限流按会话隔离：一个会话刷爆不影响另一个")
        void isolatedPerSession() {
            FakeUsageStore store = new FakeUsageStore();
            LlmGuard guard = guard(props(1, 1_000_000, true), store);

            assertThat(guard.check("bank:zhangsan:s1").allowed()).isTrue();
            assertThat(guard.check("bank:zhangsan:s1").allowed()).isFalse();
            assertThat(guard.check("bank:zhangsan:s2").allowed()).isTrue();
        }

        @Test
        @DisplayName("限流拒绝时不应去查预算——省一次数据库查询")
        void rateLimitShortCircuitsBudgetLookup() {
            FakeUsageStore store = new FakeUsageStore();
            LlmGuard guard = guard(props(1, 1_000_000, true), store);

            guard.check("bank:zhangsan:s1");
            store.queriedScopes.clear();
            guard.check("bank:zhangsan:s1");

            assertThat(store.queriedScopes).isEmpty();
        }
    }

    @Nested
    @DisplayName("预算层")
    class TokenBudget {

        @Test
        @DisplayName("今日用量达到上限即拒绝，且查询的是计费主体而非会话")
        void rejectsWhenDailyBudgetExhausted() {
            FakeUsageStore store = new FakeUsageStore();
            store.usedToday = 300_000;
            LlmGuard guard = guard(props(100, 300_000, true), store);

            LlmGuard.Decision decision = guard.check("bank:zhangsan:s1");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(LlmGuard.REASON_TOKEN_BUDGET);
            assertThat(store.queriedScopes).containsExactly("bank:zhangsan");
        }

        @Test
        @DisplayName("换一个新会话也不能重置预算——这正是按主体而非按会话限额的原因")
        void newSessionDoesNotResetBudget() {
            FakeUsageStore store = new FakeUsageStore();
            store.usedToday = 300_000;
            LlmGuard guard = guard(props(100, 300_000, true), store);

            assertThat(guard.check("bank:zhangsan:全新的会话").allowed()).isFalse();
        }

        @Test
        @DisplayName("未达上限则放行")
        void allowsBelowBudget() {
            FakeUsageStore store = new FakeUsageStore();
            store.usedToday = 299_999;
            LlmGuard guard = guard(props(100, 300_000, true), store);

            assertThat(guard.check("bank:zhangsan:s1").allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("总开关")
    class KillSwitch {

        @Test
        @DisplayName("关闭后既不限流也不拦预算，但仍继续记账（便于先观察再定阈值）")
        void disabledAllowsEverythingButStillRecords() {
            FakeUsageStore store = new FakeUsageStore();
            store.usedToday = 999_999_999L;
            LlmGuard guard = guard(props(1, 1, false), store);

            for (int i = 0; i < 50; i++) {
                assertThat(guard.check("bank:zhangsan:s1").allowed()).isTrue();
            }

            guard.recordUsage("bank:zhangsan:s1", "bank", new TokenUsage(100, 50), "trace-1");
            assertThat(store.written).hasSize(1);
        }
    }

    @Nested
    @DisplayName("用量记账")
    class UsageRecording {

        @Test
        @DisplayName("写入的是计费主体与模型名，且保留 traceId 便于与日志对齐")
        void recordsScopeAndModel() {
            FakeUsageStore store = new FakeUsageStore();
            LlmGuard guard = guard(props(10, 1000, true), store);

            guard.recordUsage("bank:zhangsan:s1", "bank", new TokenUsage(120, 30), "abc123");

            assertThat(store.written).containsExactly("bank:zhangsan|bank:zhangsan:s1|bank|qwen3.8-flash|120|30|abc123");
        }

        @Test
        @DisplayName("无 token 统计或用量为零时不写空账")
        void skipsEmptyUsage() {
            FakeUsageStore store = new FakeUsageStore();
            LlmGuard guard = guard(props(10, 1000, true), store);

            guard.recordUsage("bank:zhangsan:s1", "bank", null, "abc123");
            guard.recordUsage("bank:zhangsan:s1", "bank", new TokenUsage(0, 0), "abc123");

            assertThat(store.written).isEmpty();
        }
    }
}
