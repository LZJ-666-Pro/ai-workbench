package com.ai.workbench.bank.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.service.TransferService.PendingOrder;
import com.ai.workbench.bank.service.TransferService.TransferResult;
import com.ai.workbench.bank.support.BankTestDb;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 转账两段式状态机的集成测试：跑在真实 MySQL 上，带真实事务管理器。
 *
 * 为什么必须连真库：被测逻辑的价值几乎全在 SQL 语义里——`UPDATE ... WHERE status='PENDING'`
 * 的 CAS 幂等、`balance >= ?` 的乐观扣款、DECIMAL 精度、SUM 聚合。这些用 mock 测等于没测。
 *
 * 为什么用最小 Spring 上下文（而不是 @SpringBootTest）：转账链路只需要 DataSource +
 * JdbcTemplate + 事务管理器，把 LLM、SSE、Agent 装配一起拉起来只会让测试变慢变脆，
 * 且需要 API Key 才能启动。这里显式装配被测对象，@Transactional 代理依然真实生效。
 *
 * 数据库不可用时整个类跳过（见 {@link BankTestDb#available()}）。
 */
@DisplayName("转账状态机（真实 MySQL）")
class TransferServiceIT {

    private static final BigDecimal RETAIL_OPENING = new BigDecimal("10000.00");

    private static AnnotationConfigApplicationContext context;
    private static TransferService service;

    @Configuration
    @EnableTransactionManagement
    static class TransferTestConfig {

        /** 让 @Value("${bank.transfer-confirm-ttl-minutes:10}") 像生产一样解析 */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DataSource dataSource() {
            return BankTestDb.dataSource();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MeterRegistry meterRegistry() {
            // 内存注册表即可：测试关心的是「打点有没有发生」，不关心导出到哪
            return new SimpleMeterRegistry();
        }

        @Bean
        ToolAuditLogger toolAuditLogger(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
            return new ToolAuditLogger(jdbcTemplate, meterRegistry,
                    new com.ai.workbench.core.console.PlatformEventLogger(jdbcTemplate));
        }

        @Bean
        TransferService transferService(JdbcTemplate jdbcTemplate, ToolAuditLogger audit) {
            return new TransferService(jdbcTemplate, audit);
        }
    }

    @BeforeAll
    static void bootContext() {
        boolean databaseReady = BankTestDb.available();
        if (!databaseReady) {
            // CI 里必须把 TEST_MYSQL_REQUIRED 设为 true：否则数据库没起来时这 25 个用例
            // 会「静默跳过」而构建依然全绿，等于最关键的测试根本没跑。
            if ("true".equalsIgnoreCase(System.getenv("TEST_MYSQL_REQUIRED"))) {
                throw new IllegalStateException(
                        "TEST_MYSQL_REQUIRED=true 但测试库不可用：" + BankTestDb.describeTarget()
                                + "（检查 MySQL 是否启动、TEST_MYSQL_URL/账号密码是否正确）");
            }
            Assumptions.abort("MySQL 测试库不可用，跳过转账集成测试（用 TEST_MYSQL_URL 指定测试库）");
        }
        context = new AnnotationConfigApplicationContext(TransferTestConfig.class);
        service = context.getBean(TransferService.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetDatabase() {
        BankTestDb.reset();
    }

    /** 建单并回查确认码（不解析返回文案，避免测试依赖措辞） */
    private String createOrder(String memoryId, String to, String amount) {
        TransferResult result = service.createPendingOrder(
                memoryId, to, new BigDecimal(amount), "集成测试");
        assertThat(result.kind())
                .as("建单应成功，实际：%s", result.message())
                .isEqualTo("PENDING");
        return BankTestDb.latestConfirmId(memoryId);
    }

    @Nested
    @DisplayName("第一段：创建确认单（不碰钱）")
    class CreatePendingOrder {

        @Test
        @DisplayName("零售客户建单：只落一条 PENDING，两边余额都不动")
        void createsPendingOrderWithoutMovingMoney() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, new BigDecimal("200.00"), "it-test");

            assertThat(result.kind()).isEqualTo("PENDING");
            assertThat(result.message()).contains("确认单");
            assertThat(BankTestDb.statusOf(BankTestDb.latestConfirmId(BankTestDb.MEM_RETAIL)))
                    .isEqualTo("PENDING");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo(RETAIL_OPENING);
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("员工身份无法建单：连确认单都不产生")
        void staffCannotCreateOrder() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_STAFF, BankTestDb.ACC_PAYEE, new BigDecimal("100.00"), null);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("没有资金操作权限");
            assertThat(BankTestDb.countOrders(BankTestDb.MEM_STAFF)).isZero();
        }

        @Test
        @DisplayName("黑名单收款人在建单前就被拦下，不产生确认单")
        void blacklistedPayeeRejectedBeforeOrderCreation() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_BLACKLISTED, new BigDecimal("100.00"), null);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("风控名单");
            assertThat(BankTestDb.countOrders(BankTestDb.MEM_RETAIL)).isZero();
        }

        @Test
        @DisplayName("不能向本人账户转账")
        void rejectsSelfTransfer() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_RETAIL, new BigDecimal("100.00"), null);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("本人账户");
        }

        @Test
        @DisplayName("收款方不存在：返回 FAIL，不抛异常不建单")
        void unknownPayeeFails() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, "不存在的账户", new BigDecimal("100.00"), null);

            assertThat(result.kind()).isEqualTo("FAIL");
            assertThat(BankTestDb.countOrders(BankTestDb.MEM_RETAIL)).isZero();
        }

        @Test
        @DisplayName("超出个人单笔限额（5000）：建单被拒")
        void rejectsOverRetailSingleLimit() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, new BigDecimal("5000.01"), null);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("单笔");
        }

        @Test
        @DisplayName("当日累计超限：建单被拒，并回显今日已转金额")
        void rejectsOverRetailDailyLimit() {
            seedExecutedOrderToday(BankTestDb.MEM_RETAIL, BankTestDb.ACC_RETAIL, "9000.00");

            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, new BigDecimal("2000.00"), null);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("当日累计").contains("9000.00");
        }

        @Test
        @DisplayName("对公客户适用更高限额档：5 万建单成功（个人档会被拒）")
        void corporateUsesHigherLimitTier() {
            TransferResult result = service.createPendingOrder(
                    BankTestDb.MEM_CORPORATE, BankTestDb.ACC_PAYEE, new BigDecimal("50000.00"), "货款");

            assertThat(result.kind()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("未通知的确认单只会被取走一次（避免每轮流式都重发卡片）")
        void pendingOrderIsNotifiedOnlyOnce() {
            createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            List<PendingOrder> first = service.takeUnnotifiedPending(BankTestDb.MEM_RETAIL);
            List<PendingOrder> second = service.takeUnnotifiedPending(BankTestDb.MEM_RETAIL);

            assertThat(first).hasSize(1);
            assertThat(first.get(0).toAccount()).isEqualTo(BankTestDb.ACC_PAYEE);
            assertThat(second).isEmpty();
        }
    }

    @Nested
    @DisplayName("第二段：确认执行（唯一动钱入口）")
    class ConfirmOrder {

        @Test
        @DisplayName("确认成功：双方余额变动 + 双向流水 + 单据置 EXECUTED")
        void confirmMovesMoneyAndWritesLedger() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            TransferResult confirmed = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(confirmed.kind()).isEqualTo("SUCCESS");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo("9800.00");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("200.00");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("EXECUTED");
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_RETAIL)).isEqualTo(1);
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_PAYEE)).isEqualTo(1);
        }

        @Test
        @DisplayName("核心不变量：重复确认只生效一次，资金与流水都不再变化")
        void confirmingTwiceMovesMoneyOnlyOnce() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            assertThat(service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true).kind()).isEqualTo("SUCCESS");

            TransferResult second = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(second.kind()).isNotEqualTo("SUCCESS");
            assertThat(second.message()).contains("重复");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo("9800.00");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("200.00");
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_PAYEE)).isEqualTo(1);
        }

        @Test
        @DisplayName("并发确认同一张单：只有一个请求成功（CAS 抢占）")
        void concurrentConfirmOnlyOneWins() throws Exception {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Callable<String>> tasks = java.util.Collections.nCopies(threads,
                        (Callable<String>) () -> {
                            start.await();
                            return service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true).kind();
                        });
                List<Future<String>> futures = tasks.stream().map(pool::submit).toList();
                start.countDown();

                long successes = 0;
                for (Future<String> future : futures) {
                    if ("SUCCESS".equals(future.get(30, TimeUnit.SECONDS))) {
                        successes++;
                    }
                }
                assertThat(successes).as("并发确认只允许一次成功").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo("9800.00");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("200.00");
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_PAYEE)).isEqualTo(1);
        }

        @Test
        @DisplayName("取消确认单：状态置 CANCELLED，资金不动")
        void cancelDoesNotMoveMoney() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            TransferResult cancelled = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, false);

            assertThat(cancelled.message()).contains("已取消");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("CANCELLED");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo(RETAIL_OPENING);
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_RETAIL)).isZero();
        }

        @Test
        @DisplayName("重复取消：第二次不再改变状态（CAS 同样保护取消）")
        void cancellingTwiceIsIdempotent() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, false);

            TransferResult second = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, false);

            assertThat(second.message()).contains("已处理过");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("已执行的单据不能再取消（EXECUTED 是终态）")
        void executedOrderCannotBeCancelled() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            TransferResult cancelled = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, false);

            assertThat(cancelled.message()).contains("已处理过");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("EXECUTED");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("超过有效期（10 分钟）确认：拒绝并置 REJECTED，资金不动")
        void expiredOrderIsRejected() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            BankTestDb.backdateOrder(confirmId, 11);

            TransferResult result = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("已过期");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("REJECTED");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo(RETAIL_OPENING);
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("确认时余额已被耗光：复检拦下，不产生流水")
        void insufficientBalanceAtConfirmTimeIsRejected() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "5000.00");
            BankTestDb.setBalance(BankTestDb.ACC_RETAIL, "10.00");

            TransferResult result = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("余额不足");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("REJECTED");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo("10.00");
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_PAYEE)).isZero();
        }

        @Test
        @DisplayName("确认不存在的单据：返回 FAIL，不抛异常")
        void unknownConfirmIdFails() {
            TransferResult result = service.confirmOrder(
                    BankTestDb.MEM_RETAIL, "00000000-0000-0000-0000-000000000000", true);

            assertThat(result.kind()).isEqualTo("FAIL");
            assertThat(result.message()).contains("不存在");
        }
    }

    @Nested
    @DisplayName("状态查询：数据库是唯一事实来源")
    class DescribeOrder {

        @Test
        @DisplayName("待确认单据报 PENDING 并带剩余有效期")
        void reportsPendingState() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            String description = service.describeOrder(BankTestDb.MEM_RETAIL, confirmId);

            assertThat(description).contains("PENDING").contains("附言").contains("待确认");
        }

        @Test
        @DisplayName("执行后报 EXECUTED（不是凭对话记忆说还在等待确认）")
        void reportsExecutedState() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(service.describeOrder(BankTestDb.MEM_RETAIL, confirmId))
                    .contains("EXECUTED")
                    .contains("已执行成功");
        }

        @Test
        @DisplayName("过期单据被判定为 EXPIRED（惰性过期，无需定时任务也能给出正确口径）")
        void reportsExpiredState() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            BankTestDb.backdateOrder(confirmId, 11);

            assertThat(service.describeOrder(BankTestDb.MEM_RETAIL, confirmId))
                    .contains("EXPIRED")
                    .contains("重新发起");
        }

        @Test
        @DisplayName("不传确认码：回落到本会话最近一张单")
        void fallsBackToLatestOrderOfSession() {
            createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            assertThat(service.describeOrder(BankTestDb.MEM_RETAIL, null)).contains("PENDING");
            assertThat(service.describeOrder(BankTestDb.MEM_RETAIL, "  ")).contains("PENDING");
        }

        @Test
        @DisplayName("会话没有任何单据：明确回答没有，而不是编造")
        void reportsNoOrder() {
            assertThat(service.describeOrder(BankTestDb.MEM_RETAIL, null)).contains("没有找到");
        }
    }

    /**
     * 越权与一致性回归。每个用例都对应一个曾经真实存在的漏洞：
     * 确认单只按 confirm_id 查（拿到别人的确认码就能动别人的钱）、
     * 员工能通过 HTTP 确认接口执行转账、付款账户消失后单据停在「已执行但没扣钱」。
     */
    @Nested
    @DisplayName("越权与一致性回归")
    class AuthorizationAndConsistency {

        @Test
        @DisplayName("拿别人的确认码确认：查不到单，资金与单据状态都不变")
        void cannotConfirmOrderOfAnotherSession() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            TransferResult result = service.confirmOrder(BankTestDb.MEM_CORPORATE, confirmId, true);

            assertThat(result.kind()).isEqualTo("FAIL");
            assertThat(result.message()).contains("不存在");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("PENDING");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo(RETAIL_OPENING);
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_RETAIL)).isZero();
        }

        @Test
        @DisplayName("拿别人的确认码查询：读不到别人的单据")
        void cannotDescribeOrderOfAnotherSession() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");

            assertThat(service.describeOrder(BankTestDb.MEM_CORPORATE, confirmId))
                    .contains("没有找到");
        }

        @Test
        @DisplayName("员工身份即使拿到确认单也无法执行（HTTP 入口自己兜底，不依赖工具层）")
        void staffCannotConfirmEvenWithValidOrder() {
            String confirmId = java.util.UUID.randomUUID().toString();
            BankTestDb.jdbc().update("""
                    INSERT INTO bank_transfer_order
                        (confirm_id, memory_id, from_account, to_account, amount, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """, confirmId, BankTestDb.MEM_STAFF, BankTestDb.ACC_RETAIL,
                    BankTestDb.ACC_PAYEE, new BigDecimal("100.00"));

            TransferResult result = service.confirmOrder(BankTestDb.MEM_STAFF, confirmId, true);

            assertThat(result.kind()).isEqualTo("DENY");
            assertThat(result.message()).contains("没有资金操作权限");
            assertThat(BankTestDb.statusOf(confirmId)).isEqualTo("PENDING");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo(RETAIL_OPENING);
        }

        @Test
        @DisplayName("日累计按执行时间归集：把创建时间改到昨天也不能让今天额度被重复使用")
        void dailyLimitCountsExecutionTimeNotCreationTime() {
            String first = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "4000.00");
            assertThat(service.confirmOrder(BankTestDb.MEM_RETAIL, first, true).kind()).isEqualTo("SUCCESS");
            String second = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "4000.00");
            assertThat(service.confirmOrder(BankTestDb.MEM_RETAIL, second, true).kind()).isEqualTo("SUCCESS");

            // 创建时间挪到昨天：若按 created_at 归集，今天会被认为一分未转，第三笔 4000 就会被放行
            BankTestDb.jdbc().update(
                    "UPDATE bank_transfer_order SET created_at = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE memory_id = ?",
                    BankTestDb.MEM_RETAIL);

            TransferResult third = service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, new BigDecimal("4000.00"), null);

            assertThat(third.kind()).isEqualTo("DENY");
            assertThat(third.message()).contains("当日累计");
        }

        @Test
        @DisplayName("并发确认多张单也不能突破日限额：限额 10000、每笔 2000，只可能成功五笔")
        void concurrentConfirmsCannotExceedDailyLimit() throws Exception {
            // 6 笔各 2000，日限额 10000 只装得下 5 笔——无论线程如何交错，成功数都是确定的 5
            List<String> confirmIds = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                confirmIds.add(createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "2000.00"));
            }

            ExecutorService pool = Executors.newFixedThreadPool(confirmIds.size());
            CountDownLatch start = new CountDownLatch(1);
            long successes = 0;
            try {
                List<Future<TransferResult>> futures = confirmIds.stream()
                        .map(id -> pool.submit(() -> {
                            start.await();
                            return service.confirmOrder(BankTestDb.MEM_RETAIL, id, true);
                        }))
                        .toList();
                start.countDown();
                for (Future<TransferResult> future : futures) {
                    if ("SUCCESS".equals(future.get(60, TimeUnit.SECONDS).kind())) {
                        successes++;
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(successes).as("日限额 10000 只装得下五笔 2000").isEqualTo(5);
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_RETAIL)).isEqualByComparingTo("0.00");
            assertThat(BankTestDb.balanceOf(BankTestDb.ACC_PAYEE)).isEqualByComparingTo("10000.00");
        }

        @Test
        @DisplayName("付款账户已不存在：单据落到 REJECTED，不会停在「已执行却没扣钱」")
        void missingPayerAccountLeavesNoInconsistentState() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            BankTestDb.jdbc().update("DELETE FROM bank_account WHERE account_no = ?", BankTestDb.ACC_RETAIL);

            TransferResult result = service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(result.kind()).isEqualTo("FAIL");
            assertThat(result.message()).contains("付款账户不存在");
            assertThat(BankTestDb.statusOf(confirmId))
                    .as("不能停在 EXECUTED：那意味着单据宣称已执行、实际一分钱没动")
                    .isEqualTo("REJECTED");
            assertThat(BankTestDb.countTransactions(BankTestDb.ACC_PAYEE)).isZero();
        }
    }

    @Nested
    @DisplayName("审计留痕")
    class AuditTrail {

        @Test
        @DisplayName("建单被风控拒绝也会落审计")
        void deniedCreationIsAudited() {
            service.createPendingOrder(
                    BankTestDb.MEM_RETAIL, BankTestDb.ACC_BLACKLISTED, new BigDecimal("100.00"), null);

            assertThat(BankTestDb.auditResults("transfer")).contains("DENY");
        }

        @Test
        @DisplayName("重复确认被幂等拦截会落 IDEMPOTENT_SKIP 审计")
        void idempotentSkipIsAudited() {
            String confirmId = createOrder(BankTestDb.MEM_RETAIL, BankTestDb.ACC_PAYEE, "200.00");
            service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);
            service.confirmOrder(BankTestDb.MEM_RETAIL, confirmId, true);

            assertThat(BankTestDb.auditResults("transfer.confirm"))
                    .contains("SUCCESS")
                    .contains("IDEMPOTENT_SKIP");
        }
    }

    private static void seedExecutedOrderToday(String memoryId, String from, String amount) {
        BankTestDb.jdbc().update("""
                INSERT INTO bank_transfer_order
                    (confirm_id, memory_id, from_account, to_account, amount, status, executed_at, created_at)
                VALUES (UUID(), ?, ?, ?, ?, 'EXECUTED', NOW(), NOW())
                """, memoryId, from, BankTestDb.ACC_PAYEE, new BigDecimal(amount));
    }
}
