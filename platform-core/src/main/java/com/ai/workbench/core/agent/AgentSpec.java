package com.ai.workbench.core.agent;

import java.util.List;
import java.util.function.Function;

import dev.langchain4j.service.tool.ToolProvider;

/**
 * 一个 Agent 的声明：各应用注册自己的 Agent（名字 + 系统提示词 + 工具集），
 * 由 platform-core 的 AgentRegistry 统一装配。
 *
 * @param name             Agent 标识，同时用于 URL 路由（/api/chat/{name}/stream）和记忆隔离
 * @param systemPrompt     系统提示词（自定义器为空时的默认提示词）
 * @param tools            绑定的 @Tool 工具对象（可为空）
 * @param toolProvider     动态供具器（可空）。相比静态 tools，它能拿到「本次会话」的 memoryId，
 *                         适合工具执行需要会话上下文的场景（如审计关联、按会话生成确认单）
 * @param promptCustomizer 按会话/记忆动态定制提示词（可空）。输入为 memoryId，输出为该会话的完整
 *                         系统提示词；为空时使用 systemPrompt。多服务对象应用用它按身份注入不同提示词
 */
public record AgentSpec(
        String name,
        String systemPrompt,
        List<Object> tools,
        ToolProvider toolProvider,
        Function<Object, String> promptCustomizer) {

    /** 占位注册：仅名字 + 提示词（无工具） */
    public AgentSpec(String name, String systemPrompt) {
        this(name, systemPrompt, List.of(), null, null);
    }

    /** 常规注册：静态 @Tool 工具对象 */
    public AgentSpec(String name, String systemPrompt, List<Object> tools) {
        this(name, systemPrompt, tools, null, null);
    }

    /** 高级注册：ToolProvider 动态供具（可感知 memoryId） */
    public AgentSpec(String name, String systemPrompt, ToolProvider toolProvider) {
        this(name, systemPrompt, List.of(), toolProvider, null);
    }

    /** 多服务对象注册：ToolProvider 动态供具 + 按 memoryId 动态提示词 */
    public AgentSpec(String name, String systemPrompt, ToolProvider toolProvider,
                     Function<Object, String> promptCustomizer) {
        this(name, systemPrompt, List.of(), toolProvider, promptCustomizer);
    }

    /** 按会话（memoryId）返回系统提示词 */
    public String promptFor(Object memoryId) {
        if (promptCustomizer == null) {
            return systemPrompt;
        }
        String customized = promptCustomizer.apply(memoryId);
        return customized == null ? systemPrompt : customized;
    }
}