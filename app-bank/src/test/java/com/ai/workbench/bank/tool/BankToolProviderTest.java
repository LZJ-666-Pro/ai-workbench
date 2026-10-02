package com.ai.workbench.bank.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ai.workbench.bank.audit.ToolAuditLogger;
import com.ai.workbench.bank.identity.BankIdentity;
import com.ai.workbench.bank.service.Account;
import com.ai.workbench.bank.service.DbBankService;
import com.ai.workbench.bank.service.TransferService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 工具权限边界单测——「模型能看到哪些工具」是资金安全的第一道门。
 *
 * 这里钉住一条硬约束：内部员工身份永远拿不到转账工具。这不是提示词里的一句请求，
 * 而是供具阶段就不可见的能力，因此必须有测试守着，避免有人「顺手把 transferTools
 * 加回列表」而没人发现。
 *
 * 协作者用显式测试替身而不是 Mockito：这些类只有两三个方法，手写替身更易读，
 * 且不依赖 Mockito 运行时字节码编织（JDK 高版本下 inline mock maker 需自附加
 * java agent，在不允许自附加的环境里会直接报错，属于无谓的脆弱性）。
 */
class BankToolProviderTest {

    private static final String MEM_RETAIL = "bank:zhangsan:unit";
    private static final String MEM_STAFF = "bank:staff001:unit";
    private static final String MEM_CORPORATE = "bank:corp001:unit";

    private FakeBankService bankService;
    private FakeAuditLogger audit;
    private BankToolProvider provider;

    @BeforeEach
    void setUp() {
        bankService = new FakeBankService();
        audit = new FakeAuditLogger();
        // TransferTools 在本类用例里不会被调用（员工身份下它根本不注册）
        provider = new BankToolProvider(new AccountTools(bankService, audit), new TransferTools(null));
    }

    @AfterEach
    void tearDown() {
        ToolCallContext.clear();
    }

    private ToolProviderResult toolsFor(String memoryId) {
        return provider.provideTools(new ToolProviderRequest(memoryId, UserMessage.from("测试")));
    }

    private Set<String> toolNamesFor(String memoryId) {
        return toolsFor(memoryId).tools().keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("按身份裁剪工具集")
    class ToolSetPerIdentity {

        @Test
        @DisplayName("零售客户：能查能转，但看不到员工专属的全行概况")
        void retailSeesCustomerTools() {
            assertThat(toolNamesFor(MEM_RETAIL))
                    .containsExactlyInAnyOrder("listAccounts", "queryAccount", "transfer", "queryTransferOrder")
                    .doesNotContain("bankOverview");
        }

        @Test
        @DisplayName("内部员工：完全没有资金操作工具（转账工具根本不暴露给模型）")
        void staffHasNoMoneyTools() {
            assertThat(toolNamesFor(MEM_STAFF))
                    .containsExactlyInAnyOrder("listAccounts", "queryAccount", "bankOverview")
                    .doesNotContain("transfer", "queryTransferOrder");
        }

        @Test
        @DisplayName("对公客户：与零售同样具备转账工具（限额差异在风控层，不在工具层）")
        void corporateSeesCustomerTools() {
            assertThat(toolNamesFor(MEM_CORPORATE))
                    .containsExactlyInAnyOrder("listAccounts", "queryAccount", "transfer", "queryTransferOrder")
                    .doesNotContain("bankOverview");
        }

        @Test
        @DisplayName("未识别的 memoryId 按零售处理，不会意外拿到员工的全行视角")
        void unknownMemoryIdFallsBackToRetail() {
            assertThat(toolNamesFor("demo")).doesNotContain("bankOverview");
        }

        @Test
        @DisplayName("员工身份下取不到转账工具的执行器")
        void staffCannotResolveTransferExecutor() {
            assertThat(toolsFor(MEM_STAFF).toolExecutorByName("transfer")).isNull();
        }
    }

    @Nested
    @DisplayName("工具执行时的上下文注入")
    class ToolExecutionContext {

        @Test
        @DisplayName("员工执行 listAccounts：身份已注入上下文（读到全行账户），且执行后 ThreadLocal 被清理")
        void staffListAccountsUsesStaffIdentityAndClearsContext() {
            bankService.allAccountsResult = "62220001（张三）余额 1.00 元";
            ToolProviderResult result = toolsFor(MEM_STAFF);

            String output = result.toolExecutorByName("listAccounts").execute(execution("listAccounts"), null);

            assertThat(output).isEqualTo("全行账户：62220001（张三）余额 1.00 元");
            // 身份确实进了上下文：只有员工身份才会走 allAccounts 分支
            assertThat(bankService.allAccountsCalls).isEqualTo(1);
            assertThat(audit.entries).containsExactly(
                    new FakeAuditLogger.Entry(MEM_STAFF, "listAccounts", "identity=staff001", "SUCCESS"));
            // 执行结束必须清理，否则同线程的下一个请求会继承上一个会话的身份
            assertThat(ToolCallContext.currentMemoryId()).isEqualTo("-");
            assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.RETAIL);
        }

        @Test
        @DisplayName("零售执行 listAccounts：只读本人绑定账户，不会查全行")
        void retailListAccountsIsScopedToBoundAccount() {
            bankService.findResult = Optional.of(
                    new Account("62220001", "张三", new BigDecimal("1.00"), List.of()));
            ToolProviderResult result = toolsFor(MEM_RETAIL);

            result.toolExecutorByName("listAccounts").execute(execution("listAccounts"), null);

            assertThat(bankService.findQueries).containsExactly("62220001");
            assertThat(bankService.allAccountsCalls).isZero();
        }

        @Test
        @DisplayName("工具底层抛异常时上下文同样被清理（finally 兜底）")
        void contextClearedEvenWhenToolThrows() {
            bankService.allAccountsError = new IllegalStateException("数据库抖动");
            ToolProviderResult result = toolsFor(MEM_STAFF);

            // 异常是被框架包装还是原样抛出由 LangChain4j 决定，本用例只关心：
            // 无论走哪条路径，ThreadLocal 都不能把身份留给下一个请求
            try {
                result.toolExecutorByName("listAccounts").execute(execution("listAccounts"), null);
            } catch (RuntimeException ignored) {
                // 预期之内
            }

            assertThat(bankService.allAccountsCalls).isEqualTo(1);
            assertThat(ToolCallContext.currentMemoryId()).isEqualTo("-");
            assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.RETAIL);
        }
    }

    private static ToolExecutionRequest execution(String name) {
        return ToolExecutionRequest.builder().name(name).arguments("{}").build();
    }

    /** DbBankService 的替身：只实现 AccountTools 真正用到的两个查询 */
    static final class FakeBankService extends DbBankService {

        private int allAccountsCalls;
        private final List<String> findQueries = new ArrayList<>();
        private String allAccountsResult = "";
        private RuntimeException allAccountsError;
        private Optional<Account> findResult = Optional.empty();

        FakeBankService() {
            super(null);
        }

        @Override
        public String allAccounts() {
            allAccountsCalls++;
            if (allAccountsError != null) {
                throw allAccountsError;
            }
            return allAccountsResult;
        }

        @Override
        public Optional<Account> find(String accountNoOrOwner) {
            findQueries.add(accountNoOrOwner);
            return findResult;
        }
    }

    /** ToolAuditLogger 的替身：记录落到内存，便于断言「审计到了谁、什么结果」 */
    static final class FakeAuditLogger extends ToolAuditLogger {

        record Entry(String memoryId, String toolName, String detail, String result) {
        }

        private final List<Entry> entries = new ArrayList<>();

        FakeAuditLogger() {
            // 替身只覆写 record，父类的 jdbc / 注册表都不会被用到，
            // 但构造参数仍需给全（注册表给个内存实现即可，不引入 mock 框架）
            super(null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }

        @Override
        public void record(String memoryId, String toolName, String detail, String result) {
            entries.add(new Entry(memoryId, toolName, detail, result));
        }
    }
}
