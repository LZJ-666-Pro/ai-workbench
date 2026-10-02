package com.ai.workbench.core.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 成本与限流护栏配置（前缀 ai.guard）。
 *
 * 分层设计：限流挡「刷」，预算挡「烧」。
 *   - 限流按会话（memoryId）：挡的是脚本/前端死循环这类高频请求；
 *   - 预算按计费主体（memoryId 去掉会话段）：挡的是换会话继续刷、或单次对话
 *     因上下文不断增长而烧掉大量 token。
 * 两者维度不同，不能合并成一个阈值。
 */
@ConfigurationProperties(prefix = "ai.guard")
public class LlmGuardProperties {

    /**
     * 是否启用拦截（限流 + 预算）。关闭后不再拦截，但用量仍然记账——
     * 先观察一段时间真实用量再定阈值，比一上来就拦要稳妥。
     */
    private boolean enabled = true;

    /**
     * 单个会话每分钟允许的对话请求数。
     * 默认 20：正常人手速连续提问也到不了，而脚本一秒钟就能打出几十条。
     */
    private int requestsPerMinute = 20;

    /**
     * 单个计费主体每日 token 上限（输入 + 输出）。
     * 默认 30 万：一次普通问答约 2~3 千 token，够约 100 轮；
     * 带长上下文与多轮工具调用时会更快消耗，按实际业务调整。
     */
    private long dailyTokenLimit = 300_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public void setDailyTokenLimit(long dailyTokenLimit) {
        this.dailyTokenLimit = dailyTokenLimit;
    }
}
