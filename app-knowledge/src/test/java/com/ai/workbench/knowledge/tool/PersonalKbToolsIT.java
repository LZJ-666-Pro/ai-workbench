package com.ai.workbench.knowledge.tool;

import java.util.Map;

import com.ai.workbench.knowledge.support.KnowledgeTestDb;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 个人知识检索工具集成测试：直连真实 MySQL（与 BankTestDb 同款离线策略），
 * 不启 Spring 容器——PersonalKbTools 只依赖 JdbcTemplate 与 HybridSearchService，
 * 手工装配即可；embedding 相关的 ObjectProvider 桩成空（getIfAvailable 返回 null），
 * HybridSearchService 自动退化为纯关键词召回，正好覆盖降级路径。
 */
class PersonalKbToolsIT {

    /** 夹具问题：gram 同时命中 alice 的个人库与平台库，不命中 bob 的库 */
    private static final String QUESTION = "pgvector 混合检索和入职邮箱怎么配置";

    private static PersonalKbTools tools;
    private static KnowledgeToolProvider provider;

    /** 空的 ObjectProvider：Bean 未装配的等价物（向量路关闭） */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    @BeforeAll
    static void setUp() {
        // MySQL 不可用时整体跳过（不失败）：没有数据库的机器上 mvn test 依然绿
        Assumptions.assumeTrue(KnowledgeTestDb.available(),
                "MySQL 不可用，跳过：" + KnowledgeTestDb.describeTarget());
        var hybrid = new com.ai.workbench.core.kb.HybridSearchService(
                emptyProvider(), emptyProvider(), new com.ai.workbench.core.kb.TextIndexer());
        JdbcTemplate jdbc = KnowledgeTestDb.jdbc();
        tools = new PersonalKbTools(jdbc, hybrid);
        provider = new KnowledgeToolProvider(tools);
    }

    @BeforeEach
    void seed() {
        KnowledgeTestDb.reset();
        long aliceSpace = KnowledgeTestDb.insertSpace("我的知识库（alice）", "alice", "PERSONAL");
        KnowledgeTestDb.insertDocWithChunk(aliceSpace, "pgvector 检索笔记", "alice",
                "向量检索用 pgvector 存储，混合检索按关键词与语义两路召回，RRF 融合排名。");
        long bobSpace = KnowledgeTestDb.insertSpace("我的知识库（bob）", "bob", "PERSONAL");
        KnowledgeTestDb.insertDocWithChunk(bobSpace, "咖啡冲煮记录", "bob",
                "香草拿铁要用九十度热水冲泡，咖啡豆研磨到白砂糖粗细口感最好。");
        long platformSpace = KnowledgeTestDb.insertSpace("平台制度库", "admin", "PLATFORM");
        KnowledgeTestDb.bindAgent(platformSpace, "knowledge");
        KnowledgeTestDb.insertDocWithChunk(platformSpace, "新员工入职指引", "admin",
                "入职当天先到 IT 领取办公电脑，并在内网门户开通企业邮箱账号。");
    }

    @Test
    void aliceHitsOwnAndPlatformButNotBob() {
        KnowledgeToolContext.set("alice");
        try {
            String result = tools.searchMyKnowledge(QUESTION);
            assertThat(result).contains("pgvector 检索笔记").contains("个人库");
            assertThat(result).contains("新员工入职指引").contains("平台库");
            // 数据权限核心断言：bob 的个人内容绝不可出现在 alice 的检索结果里
            assertThat(result).doesNotContain("拿铁");
        } finally {
            KnowledgeToolContext.clear();
        }
    }

    @Test
    void noLibrariesReturnsGuidance() {
        KnowledgeTestDb.reset(); // 清空全部空间与绑定：既无个人库也无平台库
        KnowledgeToolContext.set("alice");
        try {
            String result = tools.searchMyKnowledge(QUESTION);
            assertThat(result).contains("暂无可检索的知识库");
        } finally {
            KnowledgeToolContext.clear();
        }
    }

    @Test
    void providerFeedsUsernameFromMemoryId() throws Exception {
        // 供具器从 memoryId 解析登录名放进 ThreadLocal：这是「模型无法伪造身份」的
        // 关键链路，用真实 ToolExecutor 执行一次验证（arguments 为模型回传的 JSON）
        ToolProviderRequest request = mock(ToolProviderRequest.class);
        when(request.chatMemoryId()).thenReturn("knowledge:alice:it-session");
        ToolProviderResult result = provider.provideTools(request);
        assertThat(result.tools()).isNotEmpty();
        ToolExecutor executor = result.tools().values().iterator().next();
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .name("searchMyKnowledge")
                .arguments("{\"question\": \"" + QUESTION + "\"}")
                .build();
        String answer = executor.execute(toolRequest, null);
        // 走供具器路径拿到的是 alice 的身份：命中她的个人库，且不含 bob 的内容
        assertThat(answer).contains("pgvector 检索笔记").doesNotContain("拿铁");
    }
}
