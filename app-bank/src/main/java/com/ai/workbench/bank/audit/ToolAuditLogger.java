package com.ai.workbench.bank.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 工具调用审计：每次工具调用（成功/拒绝/异常）全量落库。
 * 工具层是「模型意图」与「真实业务动作」的边界，审计挂在这里而不是业务层，
 * 这样 query 等只读调用也有迹可循。Phase 2 将泛化为平台级审计中心。
 */
@Component
public class ToolAuditLogger {

    private final JdbcTemplate jdbc;

    public ToolAuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String memoryId, String toolName, String detail, String result) {
        jdbc.update(
                "INSERT INTO bank_audit_log (memory_id, tool_name, detail, result) VALUES (?, ?, ?, ?)",
                memoryId == null || memoryId.isBlank() ? "-" : memoryId,
                toolName,
                detail == null ? "-" : detail.substring(0, Math.min(detail.length(), 500)),
                result);
    }
}
