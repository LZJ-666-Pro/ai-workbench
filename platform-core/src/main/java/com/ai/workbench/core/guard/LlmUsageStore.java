package com.ai.workbench.core.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LLM 用量台账读写（表 llm_usage，见 V5 迁移）。
 *
 * 日期口径固定在数据库侧（CURDATE()），与转账日限额、后台统计保持一致：
 * 若改用 JVM 的 LocalDate.now()，容器跑 UTC 时「今天」会从北京时间 08:00 才开始，
 * 每天多出 8 小时窗口让配额被重复使用。
 */
@Component
public class LlmUsageStore {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageStore.class);

    private final JdbcTemplate jdbc;

    public LlmUsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一笔用量。刻意不抛异常：记账失败不应该把已经成功的对话变成报错——
     * 护栏是保护性设施，不能比它保护的业务更脆弱。失败只留日志与告警指标。
     */
    public void record(String scope, String memoryId, String agent, String model,
                       int inputTokens, int outputTokens, String traceId) {
        try {
            jdbc.update("""
                    INSERT INTO llm_usage
                        (usage_scope, memory_id, agent, model, input_tokens, output_tokens, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, scope, memoryId, agent, model, inputTokens, outputTokens, traceId);
        } catch (Exception e) {
            log.warn("LLM 用量记账失败（不影响本次对话）: scope={}, memoryId={}, err={}",
                    scope, memoryId, e.getMessage());
        }
    }

    /** 该计费主体今日已用 token（输入 + 输出） */
    public long tokensUsedToday(String scope) {
        Long used = jdbc.queryForObject("""
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0)
                FROM llm_usage WHERE usage_scope = ? AND created_at >= CURDATE()
                """, Long.class, scope);
        return used == null ? 0L : used;
    }
}
