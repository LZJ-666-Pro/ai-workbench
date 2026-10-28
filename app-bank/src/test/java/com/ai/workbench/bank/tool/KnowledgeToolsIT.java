package com.ai.workbench.bank.tool;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.support.BankTestDb;
import com.ai.workbench.core.kb.TextIndexer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识检索工具的集成测试：跑在真实 MySQL 上。
 *
 * 核心价值在 SQL 语义与身份过滤的组合：绑定编码（agent_key = agent[:identity]）、
 * 「全域 + 身份绑定」的并集检索、kb_chunk JOIN kb_document 的候选组装——
 * 这些用 mock 测等于没测。语料按空间播种，身份隔离断言「对公问零售问题必须查不到」。
 *
 * 测试库结构由 BankTestDb 的 Flyway 迁移建立（V8 的 kb_* 表已就位），这里只插语料。
 */
@DisplayName("知识检索工具（真实 MySQL）")
class KnowledgeToolsIT {

    @Configuration
    static class KnowledgeTestConfig {

        @Bean
        DataSource dataSource() {
            return BankTestDb.dataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ToolAuditLogger toolAuditLogger(JdbcTemplate jdbcTemplate) {
            return new ToolAuditLogger(jdbcTemplate, new SimpleMeterRegistry(),
                    new com.ai.workbench.core.console.PlatformEventLogger(jdbcTemplate));
        }
    }

    private static AnnotationConfigApplicationContext context;
    private static KnowledgeTools tools;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(BankTestDb.available(), "MySQL 不可用，跳过");
        context = new AnnotationConfigApplicationContext(KnowledgeTestConfig.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        tools = new KnowledgeTools(jdbc, new TextIndexer(), context.getBean(ToolAuditLogger.class));
        seedCorpus(jdbc);
    }

    @AfterAll
    static void tearDown() {
        ToolCallContext.clear();
        if (context != null) {
            context.close();
        }
    }

    /** 三类语料：零售专属 / 对公专属 / 全域。问句与语料的词面重合刻意做足，让 bigram 召回稳定 */
    private static void seedCorpus(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM kb_binding");
        jdbc.update("DELETE FROM kb_chunk");
        jdbc.update("DELETE FROM kb_document");
        jdbc.update("DELETE FROM kb_space");
        seedSpace(jdbc, "IT-零售制度库", "零售转账限额制度", """
                第一条 个人账户转账实行分类限额管理，一类户手机银行单日限额一百万元。

                第二条 新开账户三十日内非柜面渠道转账限额为每日两千元，完成身份核实后自动提升。
                """, "bank:RETAIL");
        seedSpace(jdbc, "IT-对公制度库", "对公开户指引", """
                第一条 企业申请对公开户，须提供营业执照原件与法定代表人身份证件。

                第二条 企业账户不得出租出借出售给他人使用。
                """, "bank:CORPORATE");
        seedSpace(jdbc, "IT-服务规范", "客户服务规范", """
                第一条 客服实行首问负责制，首位受理人员须跟进至问题办结。

                第二条 咨询类问题应当场答复，无法当场答复的须约定回复时限。
                """, "bank");
    }

    private static void seedSpace(JdbcTemplate jdbc, String spaceName, String docTitle, String content,
                                  String agentKey) {
        jdbc.update("INSERT INTO kb_space (name, description, status, owner) VALUES (?, ?, 'READY', 'it')",
                spaceName, "IT 语料");
        Long spaceId = jdbc.queryForObject("SELECT id FROM kb_space WHERE name = ?", Long.class, spaceName);
        jdbc.update("""
                INSERT INTO kb_document (space_id, title, doc_type, words, chunks, status, enabled, content, owner)
                VALUES (?, ?, 'md', ?, 1, 'READY', 1, ?, 'it')
                """, spaceId, docTitle, content.length(), content);
        Long docId = jdbc.queryForObject(
                "SELECT id FROM kb_document WHERE space_id = ? ORDER BY id DESC LIMIT 1", Long.class, spaceId);
        jdbc.update("INSERT INTO kb_chunk (space_id, doc_id, seq, content, words) VALUES (?, ?, 0, ?, ?)",
                spaceId, docId, content, content.length());
        jdbc.update("INSERT INTO kb_binding (space_id, agent_key) VALUES (?, ?)", spaceId, agentKey);
    }

    /** 设置当前会话上下文（供具器在生产里做的事）；每个用例自己调用，@AfterAll 统一清理 */
    private static void as(String memoryId, BankIdentity identity) {
        ToolCallContext.set(memoryId, identity);
    }

    @Test
    @DisplayName("零售会话检索：命中零售专属空间")
    void retailSearchesRetailSpace() {
        as(BankTestDb.MEM_RETAIL, BankIdentity.RETAIL);
        String answer = tools.searchKnowledge("新开账户转账限额是多少");
        assertThat(answer).contains("零售转账限额制度").contains("两千元");
        assertThat(answer).doesNotContain("营业执照").as("零售身份检索不到对公专属语料");
    }

    @Test
    @DisplayName("对公会话检索：命中对公专属空间，查不到零售制度")
    void corporateSearchesCorporateOnly() {
        as(BankTestDb.MEM_CORPORATE, BankIdentity.CORPORATE);
        assertThat(tools.searchKnowledge("对公开户需要什么材料")).contains("营业执照");
        // 问句的词面（手机银行）与对公/全域语料零交集，命中缺失只能是因为零售空间不可见
        assertThat(tools.searchKnowledge("手机银行一天最多能转多少"))
                .as("对公身份检索不到零售专属语料——这正是身份区分的价值")
                .contains("未检索到");
    }

    @Test
    @DisplayName("全域绑定对三种身份可见：员工只能检索全域语料")
    void staffSeesGlobalSpaceOnly() {
        as(BankTestDb.MEM_STAFF, BankIdentity.STAFF);
        assertThat(tools.searchKnowledge("首问负责制是什么")).contains("客户服务规范");
        assertThat(tools.searchKnowledge("对公开户需要什么材料"))
                .as("员工没有专属语料时只能查到全域绑定")
                .contains("未检索到");
    }

    @Test
    @DisplayName("检索不到时如实说明，不编造")
    void truthfulWhenNothingHit() {
        as(BankTestDb.MEM_RETAIL, BankIdentity.RETAIL);
        assertThat(tools.searchKnowledge("量子力学薛定谔方程怎么解")).contains("未检索到");
    }
}
