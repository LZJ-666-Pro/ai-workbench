package com.ai.workbench.bank.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.workbench.bank.identity.BankIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具调用上下文的单测。
 *
 * 这个 ThreadLocal 决定了工具方法「以为自己在为谁服务」，取值失败会直接退化成 RETAIL，
 * 所以默认值行为和清理行为都需要被钉住——尤其是清理：漏清理会把上一个会话的身份
 * 泄露给同一线程上的下一个请求。
 */
class ToolCallContextTest {

    @AfterEach
    void tearDown() {
        ToolCallContext.clear();
    }

    @Test
    @DisplayName("未设置上下文时：memoryId 返回占位符 -，身份回退 RETAIL")
    void defaultsWhenUnset() {
        assertThat(ToolCallContext.currentMemoryId()).isEqualTo("-");
        assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.RETAIL);
    }

    @Test
    @DisplayName("set 之后能读回同一份 memoryId 与身份")
    void setThenRead() {
        ToolCallContext.set("bank:staff001:abc", BankIdentity.STAFF);
        assertThat(ToolCallContext.currentMemoryId()).isEqualTo("bank:staff001:abc");
        assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.STAFF);
    }

    @Test
    @DisplayName("clear 之后回到默认值（身份不跨调用泄露）")
    void clearRestoresDefaults() {
        ToolCallContext.set("bank:corp001:abc", BankIdentity.CORPORATE);
        ToolCallContext.clear();
        assertThat(ToolCallContext.currentMemoryId()).isEqualTo("-");
        assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.RETAIL);
    }

    @Test
    @DisplayName("空白 memoryId 也返回占位符 -，调用方无需判空")
    void blankMemoryIdFallsBackToPlaceholder() {
        ToolCallContext.set("   ", BankIdentity.RETAIL);
        assertThat(ToolCallContext.currentMemoryId()).isEqualTo("-");
    }

    @Test
    @DisplayName("后一次 set 覆盖前一次（同一线程内串行调用不会串味）")
    void laterSetOverwritesEarlier() {
        ToolCallContext.set("bank:zhangsan:a", BankIdentity.RETAIL);
        ToolCallContext.set("bank:staff001:b", BankIdentity.STAFF);
        assertThat(ToolCallContext.currentMemoryId()).isEqualTo("bank:staff001:b");
        assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.STAFF);
    }

    @Test
    @DisplayName("上下文按线程隔离：主线程设置不会影响工作线程")
    void isIsolatedPerThread() throws Exception {
        ToolCallContext.set("bank:corp001:main", BankIdentity.CORPORATE);
        BankIdentity[] seenInWorker = new BankIdentity[1];
        Thread worker = new Thread(() -> seenInWorker[0] = ToolCallContext.currentIdentity());
        worker.start();
        worker.join();
        assertThat(seenInWorker[0]).isEqualTo(BankIdentity.RETAIL);
        assertThat(ToolCallContext.currentIdentity()).isEqualTo(BankIdentity.CORPORATE);
    }
}
