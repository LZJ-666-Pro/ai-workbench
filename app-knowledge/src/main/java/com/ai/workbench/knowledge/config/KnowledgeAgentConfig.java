package com.ai.workbench.knowledge.config;

import com.ai.workbench.core.agent.AgentSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册知识库 Agent。Phase 2 在这里追加：路由 Agent、
 * 多源 @Tool（笔记/代码/收藏）、引用溯源展示。
 */
@Configuration
public class KnowledgeAgentConfig {

    @Bean
    public AgentSpec knowledgeAgent() {
        return new AgentSpec("knowledge", """
                你是个人知识库助手（当前为 Phase 0 占位版本）。
                Phase 2 计划：接入多源知识检索（学习笔记 / 代码仓库 / 网页收藏），
                由路由 Agent 决定查询哪个知识源，并在回答下方展示引用来源。
                在检索能力接入前，请基于通用知识回答，
                并主动说明你目前还无法检索用户的个人知识库。
                """);
    }
}
