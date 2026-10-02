package com.ai.workbench.core.agent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ai.workbench.core.config.LlmProperties;
import com.ai.workbench.core.observability.TraceContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
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
                builder.toolProvider(withTraceContext(spec.toolProvider()));
            } else if (!spec.tools().isEmpty()) {
                builder.tools(spec.tools());
            }
            assistants.put(spec.name(), builder.build());
        }
    }

    /**
     * 给工具执行套上 traceId：工具调用由 LangChain4j 在内部线程池上发起，
     * 请求线程的 MDC 不会跟过去，于是工具内部的日志与审计落库都会丢掉 traceId。
     *
     * 放在底座而不是各应用的 ToolProvider 里：这是「每次工具调用都属于发起它的那次请求」
     * 这一平台级约定，写在底座才能保证以后新增的应用不用各自记得做一遍。
     * 具体机制见 {@link TraceContext#forMemoryId}。
     */
    private static ToolProvider withTraceContext(ToolProvider delegate) {
        return request -> {
            ToolProviderResult provided = delegate.provideTools(request);
            String memoryId = String.valueOf(request.chatMemoryId());
            ToolProviderResult.Builder builder = ToolProviderResult.builder();
            provided.tools().forEach((specification, executor) ->
                    builder.add(specification, (toolRequest, memoryIdArg) ->
                            TraceContext.callWith(TraceContext.forMemoryId(memoryId),
                                    () -> executor.execute(toolRequest, memoryIdArg))));
            // 重建结果时不能丢掉原结果的立即返回标记，否则工具行为会变
            builder.immediateReturnToolNames(provided.immediateReturnToolNames());
            return builder.build();
        };
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
