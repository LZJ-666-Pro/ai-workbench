package com.ai.workbench.core.guard;

import java.time.Duration;

import com.ai.workbench.core.config.LlmProperties;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 成本与限流护栏：所有对话请求进模型之前的最后一道闸。
 *
 * 两层拦截，维度不同：
 *   1) 限流按会话（memoryId）——挡脚本与前端死循环这类高频请求；
 *   2) 预算按计费主体（{@link #scopeOf}）——挡「换个会话继续刷」以及
 *      单次对话因上下文持续增长而烧掉大量 token。
 *   若预算也按会话算，用户新开一个会话就能重置，等于没有。
 *
 * 挂在对话入口而不是模型监听器里：监听器只知道「一次模型调用花了多少 token」，
 * 不知道「这是谁的第几次请求」，无法据此拒绝；而入口处两样都知道，
 * 且能在真正调用模型之前就拒绝掉——拦在花钱之前才有意义。
 */
@Component
@EnableConfigurationProperties(LlmGuardProperties.class)
public class LlmGuard {

    /** 拒绝原因（指标标签值） */
    public static final String REASON_RATE_LIMIT = "rate_limit";
    public static final String REASON_TOKEN_BUDGET = "token_budget";

    /** 一次决策的结果；allowed 为 false 时 message 面向最终用户 */
    public record Decision(boolean allowed, String reason, String message) {

        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision deny(String reason, String message) {
            return new Decision(false, reason, message);
        }
    }

    private final LlmGuardProperties properties;
    private final LlmUsageStore usageStore;
    private final LlmProperties llmProperties;
    private final SlidingWindowRateLimiter rateLimiter;

    public LlmGuard(LlmGuardProperties properties, LlmUsageStore usageStore, LlmProperties llmProperties) {
        this.properties = properties;
        this.usageStore = usageStore;
        this.llmProperties = llmProperties;
        this.rateLimiter = new SlidingWindowRateLimiter(
                Math.max(1, properties.getRequestsPerMinute()), Duration.ofMinutes(1));
    }

    /** 进模型之前调用；返回 denied 时请求不应继续 */
    public Decision check(String memoryId) {
        if (!properties.isEnabled()) {
            return Decision.allow();
        }
        if (!rateLimiter.tryAcquire(memoryId)) {
            return Decision.deny(REASON_RATE_LIMIT,
                    "提问过于频繁（每个会话每分钟最多 %d 次），请稍后再试。"
                            .formatted(properties.getRequestsPerMinute()));
        }
        long used = usageStore.tokensUsedToday(scopeOf(memoryId));
        if (used >= properties.getDailyTokenLimit()) {
            return Decision.deny(REASON_TOKEN_BUDGET,
                    "今日 token 用量已达上限（%d），请明日再试或联系管理员。"
                            .formatted(properties.getDailyTokenLimit()));
        }
        return Decision.allow();
    }

    /** 对话结束后记一笔用量。无论护栏是否启用都记账，便于先观察再定阈值。 */
    public void recordUsage(String memoryId, String agent, TokenUsage usage, String traceId) {
        if (usage == null) {
            return;
        }
        int input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        if (input == 0 && output == 0) {
            return;
        }
        usageStore.record(scopeOf(memoryId), memoryId, agent,
                llmProperties.getModelName(), input, output, traceId);
    }

    /**
     * 计费主体：memoryId 去掉最后一段会话 id。
     *   bank:zhangsan:7f3a… → bank:zhangsan（正常三段格式）
     *   bank:7f3a…          → bank（早期两段格式没有身份段，只能整体归到应用维度）
     * 无冒号时原样返回。空值归到 "-"，与审计表对缺省会话的处理一致。
     */
    public static String scopeOf(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return "-";
        }
        int lastColon = memoryId.lastIndexOf(':');
        return lastColon > 0 ? memoryId.substring(0, lastColon) : memoryId;
    }
}
