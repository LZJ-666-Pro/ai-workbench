package com.ai.workbench.core.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可观测雏形：记录每次 LLM 调用的 token 用量与错误。
 * Phase 1 计划升级为每步 Agent 决策落库（审计日志）+ 接入 OpenTelemetry/Langfuse。
 */
public class LoggingChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModelListener.class);

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        log.debug("LLM 请求开始");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        TokenUsage usage = responseContext.chatResponse() != null
                && responseContext.chatResponse().metadata() != null
                ? responseContext.chatResponse().metadata().tokenUsage()
                : null;
        if (usage != null) {
            log.info("LLM 响应: 输入 {} tokens, 输出 {} tokens, 共 {} tokens",
                    usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        } else {
            log.info("LLM 响应完成（无 token 统计）");
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        Throwable error = errorContext.error();
        log.error("LLM 调用失败: {}", error.getMessage(), error);
    }
}
