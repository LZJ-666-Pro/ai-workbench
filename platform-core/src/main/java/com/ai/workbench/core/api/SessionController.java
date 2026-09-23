package com.ai.workbench.core.api;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.UserMessage;
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
     * 可选参数 identity：传入时只返回以 {agent}:{identity}: 开头的会话（多服务对象按身份隔离），
     * 不传时保持原行为（返回 {agent}: 下全部会话）。
     */
    @GetMapping("/{agent}")
    public List<SessionRecord> listSessions(@PathVariable String agent,
                                            @RequestParam(required = false) String identity) {
        String pattern = identity == null || identity.isBlank()
                ? agent + ":%"
                : agent + ":" + identity + ":%";
        String sql = "SELECT memory_id, content, updated_at FROM chat_memory WHERE memory_id LIKE ? ORDER BY updated_at DESC LIMIT 100";
        List<SessionRecord> sessions = jdbc.query(sql, (rs, i) -> new SessionRecord(
                        rs.getString("memory_id"),
                        extractTitle(rs.getString("content")),
                        rs.getTimestamp("updated_at")),
                pattern);
        return sessions.isEmpty() ? List.of() : sessions;
    }

    /**
     * 从会话消息 JSON 里取第一条用户消息做会话标题（类似 DeepSeek/ChatGPT 的自动命名）。
     * 截前 24 字，太长补省略号；解析失败返回空串，前端用 ID 尾段兜底。
     */
    private static String extractTitle(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            for (ChatMessage msg : ChatMessageDeserializer.messagesFromJson(contentJson)) {
                if (msg instanceof UserMessage userMsg) {
                    String text = userMsg.singleText();
                    if (text != null && !text.isBlank()) {
                        text = text.replaceAll("\\s+", " ").trim();
                        return text.length() > 24 ? text.substring(0, 24) + "…" : text;
                    }
                }
            }
        } catch (Exception ignore) {
            // 内容不是合法消息 JSON 等情况，退回空标题
        }
        return "";
    }

    /** 前端返回的会话记录（title 为自动生成的会话标题，可能为空） */
    public record SessionRecord(String memoryId, String title, java.sql.Timestamp updatedAt) {

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
