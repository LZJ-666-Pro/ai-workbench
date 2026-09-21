package com.ai.workbench.core.agent;

import java.util.List;

/**
 * 一个 Agent 的声明：各应用注册自己的 Agent（名字 + 系统提示词 + 工具集），
 * 由 platform-core 的 AgentRegistry 统一装配。
 *
 * @param name         Agent 标识，同时用于 URL 路由（/api/chat/{name}/stream）和记忆隔离
 * @param systemPrompt 系统提示词
 * @param tools        绑定的 @Tool 工具对象（可为空）
 */
public record AgentSpec(String name, String systemPrompt, List<Object> tools) {

    public AgentSpec(String name, String systemPrompt) {
        this(name, systemPrompt, List.of());
    }
}
