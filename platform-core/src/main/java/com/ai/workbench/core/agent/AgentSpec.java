package com.ai.workbench.core.agent;

import java.util.List;

import dev.langchain4j.service.tool.ToolProvider;

/**
 * 一个 Agent 的声明：各应用注册自己的 Agent（名字 + 系统提示词 + 工具集），
 * 由 platform-core 的 AgentRegistry 统一装配。
 *
 * @param name         Agent 标识，同时用于 URL 路由（/api/chat/{name}/stream）和记忆隔离
 * @param systemPrompt 系统提示词
 * @param tools        绑定的 @Tool 工具对象（可为空）
 * @param toolProvider 动态供具器（可空）。相比静态 tools，它能拿到「本次会话」的 memoryId，
 *                     适合工具执行需要会话上下文的场景（如审计关联、按会话生成确认单）
 */
public record AgentSpec(String name, String systemPrompt, List<Object> tools, ToolProvider toolProvider) {

    /** 占位注册：仅名字 + 提示词（无工具） */
    public AgentSpec(String name, String systemPrompt) {
        this(name, systemPrompt, List.of());
    }

    /** 常规注册：静态 @Tool 工具对象 */
    public AgentSpec(String name, String systemPrompt, List<Object> tools) {
        this(name, systemPrompt, tools, null);
    }

    /** 高级注册：ToolProvider 动态供具（可感知 memoryId） */
    public AgentSpec(String name, String systemPrompt, ToolProvider toolProvider) {
        this(name, systemPrompt, List.of(), toolProvider);
    }
}
