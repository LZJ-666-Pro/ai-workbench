package com.ai.workbench.core.api;

import java.util.List;

import com.ai.workbench.core.agent.AgentRegistry;
import com.ai.workbench.core.agent.Assistant;
import com.ai.workbench.core.memory.MysqlChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端加载历史消息的接口。
 * 注意：内存窗口消息和数据库消息需要一致，否则会出现消息重复或丢失的问题。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final AgentRegistry registry;
    private final MysqlChatMemoryStore memoryStore;

    public MemoryController(AgentRegistry registry, MysqlChatMemoryStore memoryStore) {
        this.registry = registry;
        this.memoryStore = memoryStore;
    }

    @GetMapping("/{agent}/{memoryId}")
    public String loadHistory(@PathVariable String agent,
                               @RequestParam(defaultValue = "bank") String targetAgent,
                               @PathVariable String memoryId) {
        if (!agent.equals(targetAgent)) {
            throw new IllegalArgumentException("Agent 不匹配");
        }
        Assistant assistant = registry.get(agent);
        List<ChatMessage> messages = assistant.chat(memoryId, "").messages();
        return ChatMessageSerializer.messagesToJson(messages);
    }
}
