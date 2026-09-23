package com.ai.workbench.bank.tool;

import java.lang.reflect.Method;
import java.util.List;

import com.ai.workbench.bank.identity.BankIdentity;
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
 * 银行 Agent 的动态供具器：每次对话请求时按「服务对象身份」组装工具集，并能把
 * 「本次会话」的 memoryId 与身份传入工具执行上下文（@MemoryId 在 @Tool 参数中会被
 * 当作普通参数暴露给模型，不可用，这是 LangChain4j 下拿会话上下文的标准姿势）。
 *
 * 权限差异第一道门：STAFF 不暴露任何资金操作工具（模型根本看不到 transfer），
 * 工具方法内部的身份校验是第二道兜底。
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
        BankIdentity identity = BankIdentity.fromMemoryId(memoryId);
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (Object toolObject : toolObjectsFor(identity)) {
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                if (isStaffOnlyTool(method) && !identity.isStaff()) {
                    continue;
                }
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                ToolExecutor delegate = new DefaultToolExecutor(toolObject, method);
                builder.add(spec, (toolRequest, memoryIdArg) -> {
                    ToolCallContext.set(memoryId, identity);
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

    /** 员工没有资金操作工具，直接不参与注册 */
    private List<Object> toolObjectsFor(BankIdentity identity) {
        return identity.isStaff() ? List.of(accountTools) : List.of(accountTools, transferTools);
    }

    /** 仅内部员工可用的工具（全行概况等） */
    private boolean isStaffOnlyTool(Method method) {
        return method.getName().equals("bankOverview");
    }
}