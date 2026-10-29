package com.ai.workbench.knowledge.tool;

import java.lang.reflect.Method;

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
 * 知识库 Agent 的动态供具器：每次对话请求时把「本次会话」memoryId 里的登录名
 * 传入工具执行上下文（镜像 BankToolProvider 的模式）。
 *
 * 为什么不直接用 @MemoryId 注解：memoryId 出现在 @Tool 方法参数里会被当作
 * 普通参数暴露给模型，模型可能填错值——由供具器从会话里取再放进 ThreadLocal，
 * 工具拿到的一定是真实会话身份，模型无法伪造。
 */
@Component
public class KnowledgeToolProvider implements ToolProvider {

    private final PersonalKbTools personalKbTools;

    public KnowledgeToolProvider(PersonalKbTools personalKbTools) {
        this.personalKbTools = personalKbTools;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String memoryId = String.valueOf(request.chatMemoryId());
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        for (Method method : personalKbTools.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
            ToolExecutor delegate = new DefaultToolExecutor(personalKbTools, method);
            builder.add(spec, (toolRequest, memoryIdArg) -> {
                KnowledgeToolContext.set(usernameOf(memoryId));
                try {
                    return delegate.execute(toolRequest, null);
                } finally {
                    // finally 清理：线程池线程会复用，残留的 username 会让下一次无关调用读到别人的身份
                    KnowledgeToolContext.clear();
                }
            });
        }
        return builder.build();
    }

    /**
     * 从 memoryId（knowledge:{username}:{uuid}）解析登录名。
     * 格式不符时返回空串：个人库定位按 owner='' 查不到，工具退化为只检索平台库，
     * 而不是抛错中断对话——拦截器已在入口保证格式，这里是纵深防御。
     */
    private String usernameOf(String memoryId) {
        String[] parts = memoryId.split(":");
        return parts.length == 3 && "knowledge".equals(parts[0]) ? parts[1] : "";
    }
}
