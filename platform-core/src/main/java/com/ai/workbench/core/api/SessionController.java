package com.ai.workbench.core.api;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口，所有应用共用：
 *   GET    /api/sessions/{agent}              — 获取指定 Agent 的所有会话列表（memoryId + 最后更新时间）
 *   DELETE /api/sessions/{agent}/{memoryId}   — 删除指定会话及其全部聊天记录
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final JdbcTemplate jdbc;

    public SessionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回指定 Agent 的所有会话列表，按最后更新时间降序。
     * 每条记录包含 memoryId 和 updated_at，前端用于渲染侧边栏会话列表。
     */
    @GetMapping("/{agent}")
    public List<SessionRecord> listSessions(@PathVariable String agent) {
        String pattern = agent + ":%";
        String sql = "SELECT memory_id, updated_at FROM chat_memory WHERE memory_id LIKE ? ORDER BY updated_at DESC LIMIT 100";
        List<SessionRecord> sessions = jdbc.query(sql, (rs, i) -> new SessionRecord(
                        rs.getString("memory_id"),
                        rs.getTimestamp("updated_at")),
                pattern);
        return sessions.isEmpty() ? List.of() : sessions;
    }

    /** 前端返回的会话记录 */
    public record SessionRecord(String memoryId, java.sql.Timestamp updatedAt) {

    }

    /** 删除结果 */
    public record DeleteResult(boolean ok, String message) {

    }

    /**
     * 删除指定会话及其全部聊天记录。
     * memoryId 必须以 {agent}: 开头，防止通过某应用的接口删除其他应用的会话。
     */
    @DeleteMapping("/{agent}/{memoryId}")
    public DeleteResult deleteSession(@PathVariable String agent, @PathVariable String memoryId) {
        if (!memoryId.startsWith(agent + ":")) {
            return new DeleteResult(false, "无效的会话 ID");
        }
        jdbc.update("DELETE FROM chat_memory WHERE memory_id = ?", memoryId);
        return new DeleteResult(true, "已删除");
    }
}
