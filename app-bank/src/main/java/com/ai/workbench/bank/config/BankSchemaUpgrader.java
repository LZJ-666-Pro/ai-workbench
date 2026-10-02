package com.ai.workbench.bank.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 老库补列：{@code bank_transfer_order.executed_at}。
 *
 * 为什么需要这个类：schema.sql 用的是 CREATE TABLE IF NOT EXISTS，对「已存在的表」不会生效，
 * 而 MySQL 8 也不支持 ADD COLUMN IF NOT EXISTS。所以只能查 information_schema 后补列，
 * 与 {@code PlatformUserSeeder.ensureStatusColumn()} 是同一套做法。
 *
 * 注意：这是权宜之计。项目正在做工程底座硬化，引入 Flyway 后本类应被 V2__ 迁移脚本取代，
 * 届时删除即可（数据回填逻辑一并搬进迁移脚本）。
 */
@Component
public class BankSchemaUpgrader implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public BankSchemaUpgrader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (hasColumn("bank_transfer_order", "executed_at")) {
            return;
        }
        jdbc.update("ALTER TABLE bank_transfer_order "
                + "ADD COLUMN executed_at TIMESTAMP NULL AFTER notified");
        // 历史 EXECUTED 单没有执行时刻，用创建时间回填：
        // 否则日累计会漏掉这些单子，限额被凭空放宽
        jdbc.update("UPDATE bank_transfer_order SET executed_at = created_at "
                + "WHERE status = 'EXECUTED' AND executed_at IS NULL");
    }

    private boolean hasColumn(String table, String column) {
        Integer columns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return columns != null && columns > 0;
    }
}
