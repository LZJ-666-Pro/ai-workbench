package com.ai.workbench.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入配置。
 * 走 OpenAI 兼容协议，DeepSeek / Qwen(DashScope 兼容模式) / GLM 都能接，换厂商只改配置不改代码。
 */
@ConfigurationProperties(prefix = "ai.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com/v1";
    private String apiKey = "";
    private String modelName = "deepseek-chat";
    private Double temperature = 0.7;
    /** 会话记忆保留的最大消息条数 */
    private Integer maxMessages = 20;

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

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(Integer maxMessages) {
        this.maxMessages = maxMessages;
    }
}
