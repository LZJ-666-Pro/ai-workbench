package com.ai.workbench.bank.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 身份解析单测。
 *
 * memoryId 是身份的唯一载体（权限、提示词、限额档位、会话隔离全部由它推导），
 * 所以「解析不出身份时回退成谁」是一个安全相关的默认值，必须钉死在测试里。
 */
class BankIdentityTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "bank:zhangsan:uuid-1, RETAIL",
            "bank:staff001:uuid-2, STAFF",
            "bank:corp001:uuid-3, CORPORATE",
    })
    @DisplayName("三段格式：按中段解析身份")
    void parsesIdentityFromThreeSegmentMemoryId(String memoryId, BankIdentity expected) {
        assertThat(BankIdentity.fromMemoryId(memoryId)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "未知/畸形 memoryId「{0}」回退 RETAIL")
    @ValueSource(strings = {
            "bank:someone-else:uuid",   // 未知身份段
            "bank:uuid-only",           // 两段旧格式
            "demo",                     // 单段（无身份信息）
            "bank:zhangsan:extra:seg",  // 段数过多
            ":",                        // 空段
            "",
    })
    @DisplayName("无法识别的 memoryId 一律回退 RETAIL（安全默认值）")
    void fallsBackToRetail(String memoryId) {
        assertThat(BankIdentity.fromMemoryId(memoryId)).isEqualTo(BankIdentity.RETAIL);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("null memoryId 不抛异常，回退 RETAIL")
    void handlesNull(String memoryId) {
        assertThat(BankIdentity.fromMemoryId(memoryId)).isEqualTo(BankIdentity.RETAIL);
    }

    @Test
    @DisplayName("只有员工不可转账，零售与对公可转账")
    void transferPermission() {
        assertThat(BankIdentity.RETAIL.canTransfer()).isTrue();
        assertThat(BankIdentity.CORPORATE.canTransfer()).isTrue();
        assertThat(BankIdentity.STAFF.canTransfer()).isFalse();
    }

    @Test
    @DisplayName("每个身份的 id 都可被自己解析回来（id 与解析规则不会漂移）")
    void idRoundTrips() {
        for (BankIdentity identity : BankIdentity.values()) {
            String memoryId = "bank:%s:uuid".formatted(identity.id());
            assertThat(BankIdentity.fromMemoryId(memoryId)).isEqualTo(identity);
        }
    }

    @Test
    @DisplayName("员工没有绑定账户，零售与对公各自绑定唯一账户")
    void boundAccounts() {
        assertThat(BankIdentity.STAFF.boundAccount()).isNull();
        assertThat(BankIdentity.RETAIL.boundAccount()).isEqualTo("62220001");
        assertThat(BankIdentity.CORPORATE.boundAccount()).isEqualTo("82280001");
    }

    @Test
    @DisplayName("对公账号按 8 开头约定，与 DbBankService 的口径一致")
    void corporateAccountUsesEightPrefix() {
        assertThat(BankIdentity.CORPORATE.boundAccount()).startsWith("8");
        assertThat(BankIdentity.RETAIL.boundAccount()).doesNotStartWith("8");
    }

    @Test
    @DisplayName("身份展示元数据完整：欢迎语非空、建议问题非空（前端直接渲染）")
    void exposesDisplayMetadata() {
        for (BankIdentity identity : BankIdentity.values()) {
            assertThat(identity.welcome()).isNotBlank();
            assertThat(identity.suggestions()).isNotEmpty();
            assertThat(identity.info().id()).isEqualTo(identity.id());
            assertThat(identity.displayName()).isNotBlank();
        }
    }
}
