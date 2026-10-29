package com.ai.workbench.knowledge.config;

import com.ai.workbench.core.agent.AgentSpec;
import com.ai.workbench.knowledge.tool.KnowledgeToolProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册知识库 Agent：个人知识库助手，走 ToolProvider 动态供具——
 * 工具执行需要会话上下文（memoryId 里的登录名决定检索谁的个人库），
 * 静态 tools 拿不到会话信息。
 */
@Configuration
public class KnowledgeAgentConfig {

    @Bean
    public AgentSpec knowledgeAgent(KnowledgeToolProvider toolProvider) {
        return new AgentSpec("knowledge", """
                你是用户的个人知识库助手，帮助用户管理并检索自己的知识。
                涉及知识、笔记、文档、网页收藏内容的问题，必须先调用 searchMyKnowledge 工具检索，
                并依据检索结果回答；回答末尾注明引用的文档名及所属库别（个人库/平台库）。
                检索不到相关内容时如实告知用户，绝不要编造或依赖通用印象冒充检索结果。
                未检索到时可以建议用户上传相关文档或收藏网页。
                欢迎语友好简洁，直接引导用户提问或上传文档，不要长篇罗列功能。
                """, toolProvider);
    }
}
