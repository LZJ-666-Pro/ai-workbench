package com.ai.workbench.core.api;

import java.util.List;

import com.ai.workbench.core.memory.MysqlChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话历史接口，所有应用共用：
 *   GET /api/memory/{agent}/{memoryId} — 指定会话的历史消息（role/content 简化格式）
 *
 * 只暴露用户可见的 user/assistant 文本：系统提示词、工具调用帧不外露；
 * 没有正文的消息（纯工具调用决策帧）跳过。agent 保留在路径里是为 URL 语义完整，
 * 当前三个应用共用一张 chat_memory 表，按 memoryId 即可定位。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MysqlChatMemoryStore memoryStore;

    public MemoryController(MysqlChatMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @GetMapping("/{agent}/{memoryId}")
    public List<HistoryMsg> history(@PathVariable String agent, @PathVariable String memoryId) {
        List<ChatMessage> messages = memoryStore.getMessages(memoryId);
        return messages.stream()
                .filter(m -> !(m instanceof SystemMessage) && !(m instanceof ToolExecutionResultMessage))
                .map(m -> {
                    if (m instanceof UserMessage user) {
                        return new HistoryMsg("user", user.singleText());
                    }
                    if (m instanceof AiMessage ai) {
                        return new HistoryMsg("assistant", ai.text() == null ? "" : ai.text());
                    }
                    return new HistoryMsg("assistant", "");
                })
                .filter(h -> h.content() != null && !h.content().isBlank())
                .toList();
    }

    /** 前端渲染用的简化消息 */
    public record HistoryMsg(String role, String content) {

    }
}
