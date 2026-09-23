package com.ai.workbench.core.agent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ai.workbench.core.config.LlmProperties;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Agent 注册中心：收集各应用声明的 AgentSpec，为每个 Agent 装配
 * 流式模型 + 持久化记忆 + 工具，产出可直接对话的 Assistant。
 */
@Component
public class AgentRegistry {

    private final Map<String, Assistant> assistants = new ConcurrentHashMap<>();

    public AgentRegistry(ObjectProvider<AgentSpec> specs,
                         StreamingChatModel streamingModel,
                         ChatMemoryStore chatMemoryStore,
                         LlmProperties llmProperties) {
        for (AgentSpec spec : specs.stream().toList()) {
            AiServices<Assistant> builder = AiServices.builder(Assistant.class)
                    .streamingChatModel(streamingModel)
                    .systemMessageProvider(memoryId -> spec.promptFor(memoryId))
                    .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                            .id(memoryId)
                            .maxMessages(llmProperties.getMaxMessages())
                            .chatMemoryStore(chatMemoryStore)
                            .build());
            if (spec.toolProvider() != null) {
                builder.toolProvider(spec.toolProvider());
            } else if (!spec.tools().isEmpty()) {
                builder.tools(spec.tools());
            }
            assistants.put(spec.name(), builder.build());
        }
    }

    public Assistant get(String name) {
        Assistant assistant = assistants.get(name);
        if (assistant == null) {
            throw new IllegalArgumentException("未注册的 Agent: " + name + "，可用: " + assistants.keySet());
        }
        return assistant;
    }

    public Set<String> names() {
        return assistants.keySet();
    }
}
