package com.ai.workbench.bank.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import com.ai.workbench.bank.console.ConsoleDtos.LogPage;
import com.ai.workbench.bank.console.ConsoleDtos.Workbench;
import com.ai.workbench.bank.support.BankTestDb;
import com.ai.workbench.core.agent.AgentRegistry;
import com.ai.workbench.core.agent.AgentSpec;
import com.ai.workbench.core.config.LlmProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 工作台读模型的集成测试：跑在真实 MySQL 上（表结构来自真实 Flyway 迁移）。
 *
 * 为什么必须连库测：这个类的价值就是「每一格数字都对应一句真实 SQL」，
 * 而 SQL 的问题（列名写错、文本块吃掉空格导致 ANDDATE、P95 取错表）**只有真跑才会暴露**——
 * 这两类错误本人在开发过程中就各踩了一次，都是启动后调接口才 500。
 * 用 mock 测等于把这个类唯一有价值的部分跳过。
 */
class PlatformConsoleServiceIT {

    private static DefaultListableBeanFactory beanFactory;

    @BeforeAll
    static void bootContext() {
        if (!BankTestDb.available()) {
            if ("true".equalsIgnoreCase(System.getenv("TEST_MYSQL_REQUIRED"))) {
                throw new IllegalStateException(
                        "TEST_MYSQL_REQUIRED=true 但测试库不可用：" + BankTestDb.describeTarget());
            }
            Assumptions.assumeTrue(false, "测试库不可用，跳过：" + BankTestDb.describeTarget());
        }
        // 只取空的对象提供者：本测试不装配任何 Agent，AgentRegistry 的构造循环不会执行，
        // 因此模型/记忆参数传 null 安全（避免为了测一句 SQL 把整个 AI 装配拉起来）
        beanFactory = new DefaultListableBeanFactory();
    }

    @AfterAll
    static void tearDown() {
        beanFactory = null;
    }

    private PlatformConsoleService service() {
        JdbcTemplate jdbc = BankTestDb.jdbc();
        AgentRegistry registry = new AgentRegistry(
                beanFactory.getBeanProvider(AgentSpec.class), null, null, new LlmProperties());
        return new PlatformConsoleService(jdbc, registry,
                beanFactory.getBeanProvider(RequestMappingHandlerMapping.class));
    }

    private JdbcTemplate jdbc() {
        return BankTestDb.jdbc();
    }

    /** 演示数据表不在 BankTestDb.reset() 的清理范围内，这里自行清空，保证断言确定 */
    @BeforeEach
    void cleanEventTables() {
        jdbc().execute("DELETE FROM platform_event_log");
        jdbc().execute("DELETE FROM knowledge_query_log");
        jdbc().execute("DELETE FROM knowledge_document");
        jdbc().execute("DELETE FROM interview_session");
        jdbc().execute("DELETE FROM llm_usage");
    }

    private void event(String app, String category, String action, String result,
                       Integer durationMs, Timestamp at) {
        jdbc().update("""
                INSERT INTO platform_event_log
                    (app, category, action, detail, result, memory_id, duration_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, app, category, action, "测试事件", result, app + ":tester:demo", durationMs, at);
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    @Nested
    @DisplayName("首页读模型")
    class WorkbenchReadModel {

        @Test
        @DisplayName("空库时返回零值而不是抛异常（首页首屏不能因为没数据就白屏）")
        void emptyDatabaseIsSafe() {
            Workbench wb = service().workbench(true);

            assertThat(wb.apps()).hasSize(3);
            assertThat(wb.hero().sseP95Ms()).as("无样本时 P95 为 null，页面显示 —").isNull();
            assertThat(wb.platform().successRate()).isZero();
            assertThat(wb.apps()).allSatisfy(app -> assertThat(app.metrics()).hasSizeGreaterThanOrEqualTo(2));
        }

        @Test
        @DisplayName("英雄区四个数字分别来自 llm_usage / 运行日志 / 会话记忆 / 工具事件")
        void heroCapsComeFromRealTables() {
            jdbc().update("""
                    INSERT INTO llm_usage (usage_scope, memory_id, agent, model, input_tokens, output_tokens)
                    VALUES ('bank:zhangsan', 'bank:zhangsan:x', 'bank', 'qwen3.8-flash', 10, 5),
                           ('bank:corp001', 'bank:corp001:x', 'bank', 'deepseek-chat', 10, 5)
                    """);
            event("bank", "chat", "agent.chat", "SUCCESS", 400, now());
            event("bank", "tool", "queryAccount", "SUCCESS", null, now());
            event("bank", "tool", "listAccounts", "SUCCESS", null, now());

            Workbench wb = service().workbench(true);

            assertThat(wb.hero().models()).isEqualTo(2);
            assertThat(wb.hero().sseP95Ms()).isEqualTo(400);
            assertThat(wb.hero().tools()).as("按动作去重，两个不同工具").isEqualTo(2);
        }

        @Test
        @DisplayName("知识库卡片的 P95 取的是 latency_ms 列（与运行日志的 duration_ms 不同名）")
        void knowledgeP95UsesLatencyColumn() {
            jdbc().update("""
                    INSERT INTO knowledge_query_log (memory_id, question, hits, latency_ms, result)
                    VALUES ('k:1', '制度检索', 3, 250, 'SUCCESS')
                    """);

            Workbench wb = service().workbench(false);

            ConsoleDtos.AppCard knowledge = wb.apps().stream()
                    .filter(a -> a.key().equals("knowledge")).findFirst().orElseThrow();
            assertThat(knowledge.metrics())
                    .anySatisfy(m -> assertThat(m.value()).isEqualTo("250ms"));
        }

        @Test
        @DisplayName("面试卡片的平均分忽略未结束（无成绩）的场次")
        void interviewAverageIgnoresOngoing() {
            jdbc().update("""
                    INSERT INTO interview_session (candidate, position, rounds, score, status, started_at)
                    VALUES ('甲', '风控岗', 3, 80, 'FINISHED', NOW()),
                           ('乙', '风控岗', 4, 90, 'FINISHED', NOW()),
                           ('丙', '风控岗', 1, NULL, 'ONGOING', NOW())
                    """);

            Workbench wb = service().workbench(false);

            ConsoleDtos.AppCard interview = wb.apps().stream()
                    .filter(a -> a.key().equals("interview")).findFirst().orElseThrow();
            assertThat(interview.metrics())
                    .anySatisfy(m -> assertThat(m.value()).isEqualTo("85"))
                    .anySatisfy(m -> assertThat(m.value()).isEqualTo("3 场"));
        }

        @Test
        @DisplayName("通知中心：ADMIN 看得到待办，普通用户拿到空列表（不越权展示运维信息）")
        void notificationsAreAdminOnly() {
            jdbc().update("""
                    INSERT INTO bank_transfer_order
                        (confirm_id, memory_id, from_account, to_account, amount, status)
                    VALUES (UUID(), 'bank:zhangsan:t', '62220001', '62220099', 100.00, 'PENDING')
                    """);

            Workbench admin = service().workbench(true);
            Workbench user = service().workbench(false);

            assertThat(admin.admin()).isTrue();
            assertThat(admin.notifications()).isNotEmpty();
            assertThat(user.notifications()).as("非管理员不返回任何通知内容").isEmpty();
        }

        @Test
        @DisplayName("平台总应用数只数三个业务应用，不含日志页/管理后台/开发者文档")
        void appCountExcludesPlatformPages() {
            // 搜索目录里还包含平台自身的功能页（共 6 条），拿它当"应用数"会把 3 说成 6
            Workbench wb = service().workbench(false);

            assertThat(wb.platform().apps()).isEqualTo(3);
            assertThat(wb.apps()).as("卡片数应与应用数一致").hasSize(3);
        }

        @Test
        @DisplayName("今日 Token 消耗取 llm_usage 台账的输入+输出之和")
        void tokensTodayComesFromUsageLedger() {
            jdbc().update("""
                    INSERT INTO llm_usage (usage_scope, memory_id, agent, model, input_tokens, output_tokens)
                    VALUES ('bank:zhangsan', 'bank:zhangsan:t', 'bank', 'qwen3.8-flash', 1200, 300)
                    """);

            assertThat(service().workbench(false).platform().tokensToday()).isEqualTo(1500);
        }

        @Test
        @DisplayName("成功率按今日事件计算，且没数据时是 0 而不是 100")
        void successRateIsHonestWhenEmpty() {
            event("bank", "tool", "queryAccount", "SUCCESS", null, now());
            event("bank", "tool", "transfer", "DENY", null, now());
            event("bank", "chat", "agent.chat", "FAIL", null, now());
            event("bank", "chat", "agent.chat", "SUCCESS", null, now());

            Workbench wb = service().workbench(false);

            assertThat(wb.platform().eventsToday()).isEqualTo(4);
            assertThat(wb.platform().successRate()).as("4 条里 2 条成功").isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("日志查询")
    class LogQuery {

        @Test
        @DisplayName("按应用过滤，且分页只影响列表、汇总始终是全量")
        void filterAndSummary() {
            for (int i = 0; i < 5; i++) {
                event("bank", "tool", "queryAccount", "SUCCESS", null, now());
            }
            event("knowledge", "search", "knowledge.search", "FAIL", 200, now());

            PlatformConsoleService service = service();
            LogPage bankPage = service.logs("bank", null, null, null, 1, 2);

            assertThat(bankPage.total()).isEqualTo(5);
            assertThat(bankPage.list()).as("size=2 只返回两条").hasSize(2);
            assertThat(bankPage.summary().total()).as("汇总是全量，不随分页变化").isEqualTo(5);
            assertThat(bankPage.summary().success()).isEqualTo(5);
            assertThat(bankPage.summary().fail()).isZero();
        }

        @Test
        @DisplayName("关键字同时匹配动作、明细、会话与 traceId")
        void keywordMatchesSeveralColumns() {
            jdbc().update("""
                    INSERT INTO platform_event_log
                        (app, category, action, detail, result, memory_id, trace_id)
                    VALUES ('bank', 'tool', 'queryAccount', '查询账户余额', 'SUCCESS',
                            'bank:zhangsan:abc', 'deadbeefdeadbeef')
                    """);

            PlatformConsoleService service = service();

            assertThat(service.logs(null, null, null, "queryAccount", 1, 20).total()).isEqualTo(1);
            assertThat(service.logs(null, null, null, "账户余额", 1, 20).total()).isEqualTo(1);
            assertThat(service.logs(null, null, null, "deadbeef", 1, 20).total()).isEqualTo(1);
            assertThat(service.logs(null, null, null, "不存在的东西", 1, 20).total()).isZero();
        }

        @Test
        @DisplayName("结果与类别筛选可叠加")
        void combinedFilters() {
            event("bank", "guard", "guard.reject", "DENY", null, now());
            event("bank", "guard", "guard.reject", "DENY", null, now());
            event("bank", "chat", "agent.chat", "SUCCESS", 100, now());

            PlatformConsoleService service = service();

            assertThat(service.logs("bank", "guard", "DENY", null, 1, 20).total()).isEqualTo(2);
            assertThat(service.logs("bank", "chat", null, null, 1, 20).total()).isEqualTo(1);
        }

        @Test
        @DisplayName("按时间倒序，最新的在最前")
        void newestFirst() {
            event("bank", "tool", "old", "SUCCESS", null, Timestamp.valueOf(LocalDateTime.now().minusHours(2)));
            event("bank", "tool", "new", "SUCCESS", null, now());

            LogPage page = service().logs("bank", null, null, null, 1, 20);

            assertThat(page.list().getFirst().action()).isEqualTo("new");
        }
    }
}
