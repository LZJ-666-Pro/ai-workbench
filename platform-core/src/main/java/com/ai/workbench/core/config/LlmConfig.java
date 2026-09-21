package com.ai.workbench.core.config;

import java.util.List;

import com.ai.workbench.core.observability.LoggingChatModelListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模型装配：阻塞式 + 流式各一个，都挂上可观测监听器。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LoggingChatModelListener chatModelListener() {
        return new LoggingChatModelListener();
    }

    /** 阻塞式模型：Phase 2 的查询改写、意图路由等内部调用用 */
    @Bean
    public ChatModel chatModel(LlmProperties props, LoggingChatModelListener listener) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .listeners(List.of(listener))
                .build();
    }

    /** 流式模型：对话主链路，SSE 逐 token 推给前端 */
    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties props, LoggingChatModelListener listener) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .temperature(props.getTemperature())
                .listeners(List.of(listener))
                .build();
    }
}
