package com.ai.workbench.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量模型配置（Phase 2 RAG 用），默认关闭。
 */
@ConfigurationProperties(prefix = "ai.embedding")
public class EmbeddingProperties {

    private boolean enabled = false;
    /** DashScope 兼容模式同样支持 OpenAI 风格的 embedding 接口 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String apiKey = "";
    private String modelName = "text-embedding-v3";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
