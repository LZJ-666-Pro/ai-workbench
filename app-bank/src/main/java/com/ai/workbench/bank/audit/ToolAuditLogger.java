package com.ai.workbench.bank.audit;

import com.ai.workbench.core.observability.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 工具调用审计：每次工具调用（成功/拒绝/异常）全量落库。
 * 工具层是「模型意图」与「真实业务动作」的边界，审计挂在这里而不是业务层，
 * 这样 query 等只读调用也有迹可循。Phase 2 将泛化为平台级审计中心。
 *
 * 同时在这里产出指标：这里是所有工具调用的唯一收口，放这里不会漏。
 * 指标补足审计表做不到的事——审计表能查到「哪一次被拒了」，但回答不了
 * 「今天风控拒绝率是不是在涨」，后者得靠按 tool/result 聚合的计数器。
 *
 * trace_id 与 memory_id 各司其职：memory_id 定位「哪个会话/客户」，
 * trace_id 定位「哪一次请求」。只有 memory_id 时，同一会话的多次提问混在一起，
 * 排查单次故障仍要靠猜时间，因此两者都落库。
 */
@Component
public class ToolAuditLogger {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public ToolAuditLogger(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    public void record(String memoryId, String toolName, String detail, String result) {
        // tool 与 result 都是有限枚举，不会造成指标基数爆炸
        meterRegistry.counter("bank.tool.calls", "tool", toolName, "result", result).increment();
        jdbc.update(
                "INSERT INTO bank_audit_log (memory_id, tool_name, detail, result, trace_id) VALUES (?, ?, ?, ?, ?)",
                memoryId == null || memoryId.isBlank() ? "-" : memoryId,
                toolName,
                detail == null ? "-" : detail.substring(0, Math.min(detail.length(), 500)),
                result,
                // 工具调用跑在 LLM 客户端线程上，traceId 由 ChatStreamController 跨线程接续过来；
                // 无请求上下文时为 null，允许为空（历史数据与后台直调都没有）
                TraceContext.current());
    }
}
