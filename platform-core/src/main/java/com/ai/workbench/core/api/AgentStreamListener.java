package com.ai.workbench.core.api;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流结束后的扩展点：业务模块可以向同一条 SSE 连接追加领域事件
 * （如银行模块推送「转账确认卡片」confirm_request）。
 * 实现为 Spring Bean 即自动注册；底座在模型回复完成后、发送 done 之前依次回调。
 */
public interface AgentStreamListener {

    /**
     * @param agent    本次对话的 Agent 标识
     * @param memoryId 本次对话的会话 ID
     * @param emitter  仍处于打开状态的 SSE 连接
     */
    void onStreamComplete(String agent, String memoryId, SseEmitter emitter);
}
