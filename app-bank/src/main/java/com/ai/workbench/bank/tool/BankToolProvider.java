package com.ai.workbench.bank.tool;

import java.lang.reflect.Method;
import java.util.List;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.stereotype.Component;

/**
 * 银行 Agent 的动态供具器：每次对话请求时组装工具，并能把「本次会话」的 memoryId
 * 传入工具执行上下文（@MemoryId 在 @Tool 参数中会被当作普通参数暴露给模型，不可用，
 * 这是 LangChain4j 下拿会话上下文的标准姿势）。
 */
@Component
public class BankToolProvider implements ToolProvider {

    private final AccountTools accountTools;
    private final TransferTools transferTools;

    public BankToolProvider(AccountTools accountTools, TransferTools transferTools) {
        this.accountTools = accountTools;
        this.transferTools = transferTools;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String memoryId = String.valueOf(request.chatMemoryId());
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (Object toolObject : List.of(accountTools, transferTools)) {
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                ToolExecutor delegate = new DefaultToolExecutor(toolObject, method);
                builder.add(spec, (toolRequest, memoryIdArg) -> {
                    ToolCallContext.set(memoryId);
                    try {
                        return delegate.execute(toolRequest, null);
                    } finally {
                        ToolCallContext.clear();
                    }
                });
            }
        }
        return builder.build();
    }
}
