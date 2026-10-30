package com.ai.workbench.bank.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.workbench.core.support.BankTestDb;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 收款账户播种的集成测试：跑在真实 MySQL 上。
 *
 * 要钉住的语义是「缺了才插、存在不碰」：演示库被用户测过几轮后余额已经变了，
 * 重启服务重新播种不能把余额打回初始值；反过来缺失的账户必须补上，
 * 否则欢迎语「给李四转 200」点进去就是「未找到收款账户」。
 */
@DisplayName("收款账户演示播种（真实 MySQL）")
class BankDemoDataSeederIT {

    @Test
    @DisplayName("缺失账户被补齐；重复执行不插重复行、不改已有数据")
    void seedsIdempotently() {
        Assumptions.assumeTrue(BankTestDb.available(), "MySQL 不可用，跳过");
        JdbcTemplate jdbc = BankTestDb.jdbc();

        // 第一轮播种：把缺失的演示账户补齐（测试库经 Flyway 重建后是空表）
        new BankDemoDataSeeder(jdbc).run(null);
        // 模拟用户测过几轮转账：把播种的初始余额改掉
        jdbc.update("UPDATE bank_account SET balance = 8888.00 WHERE account_no = '62220002'");

        // 第二轮播种（模拟服务重启）：存在即不碰
        new BankDemoDataSeeder(jdbc).run(null);

        assertThat(countDemoAccounts(jdbc))
                .as("三个演示账户恰好各一条：重复播种不插重复行")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT balance FROM bank_account WHERE account_no = '62220002'", String.class))
                .as("存在即不碰：用户测出来的余额不能被打回初始值")
                .isEqualTo("8888.00");
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM bank_account WHERE account_no = '62220003'", String.class))
                .isEqualTo("王五");
        assertThat(jdbc.queryForObject(
                "SELECT owner FROM bank_account WHERE account_no = '62220004'", String.class))
                .isEqualTo("赵六");
    }

    private static int countDemoAccounts(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bank_account WHERE account_no IN ('62220002','62220003','62220004')",
                Integer.class);
        return n == null ? 0 : n;
    }
}
