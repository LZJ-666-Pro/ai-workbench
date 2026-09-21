package com.ai.workbench.core.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 流式对话助手：memoryId 区分不同会话，返回逐 token 的流。
 */
public interface Assistant {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
}
