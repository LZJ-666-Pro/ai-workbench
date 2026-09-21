package com.ai.workbench.core.rag;

import com.ai.workbench.core.config.EmbeddingProperties;
import com.ai.workbench.core.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 管线配置位（Phase 2 启用）：
 *   ai.embedding.enabled=true  → 装配向量模型
 *   ai.rag.enabled=true        → 装配 pgvector 向量库
 * 两项默认关闭，应用启动时不会连接 Postgres。
 * 检索增强器（查询改写、混合检索、重排）在 Phase 2 基于这两个 Bean 搭建。
 */
@Configuration
@EnableConfigurationProperties({EmbeddingProperties.class, RagProperties.class})
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ai.embedding", name = "enabled", havingValue = "true")
    public EmbeddingModel embeddingModel(EmbeddingProperties props) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
    public EmbeddingStore<TextSegment> embeddingStore(RagProperties props) {
        return PgVectorEmbeddingStore.builder()
                .host(props.getHost())
                .port(props.getPort())
                .database(props.getDatabase())
                .user(props.getUser())
                .password(props.getPassword())
                .table(props.getTable())
                .dimension(props.getDimension())
                .build();
    }
}
