package com.ai.workbench.bank.seed;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 银行域演示数据播种：启动时补齐「收款方」账户，幂等。
 *
 * 为什么需要：转账与员工查询的演示场景（欢迎语建议「给李四转 200」「查王五的账户」）
 * 都依赖库里存在收款账户。bank_account 不纳入 Flyway 迁移——余额是运行时可变数据，
 * 放在迁移里每次重建库都会被重置；而历史账户数据是早期手工造的，换库就没了。
 * 这里按「缺了才插、存在不碰」的原则补种：不重置已有账户的余额，
 * 用户测过几轮转账后重启服务，数据不会被错误地打回初始值。
 *
 * 账户清单与欢迎语建议（BankIdentity）、风控黑名单（application.yml bank.risk.blacklist）
 * 三处呼应：李四/王五是正常收款人，赵六（62220004）用于演示风控拦截。
 */
@Component
public class BankDemoDataSeeder implements ApplicationRunner {

    /** 账号 / 户名 / 初始余额（仅首次插入时生效） */
    private static final List<String[]> DEMO_ACCOUNTS = List.of(
            new String[]{"62220002", "李四", "3620.00"},
            new String[]{"62220003", "王五", "98760.00"},
            new String[]{"62220004", "赵六", "100.00"});

    private final JdbcTemplate jdbc;

    public BankDemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String[] account : DEMO_ACCOUNTS) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bank_account WHERE account_no = ?",
                    Integer.class, account[0]);
            if (exists == null || exists == 0) {
                jdbc.update("INSERT INTO bank_account (account_no, owner, balance) VALUES (?, ?, ?)",
                        account[0], account[1], account[2]);
            }
        }
    }
}
