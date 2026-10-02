package com.ai.workbench.core.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库迁移统一配置（平台级，三个应用共用）。
 *
 * 放在 platform-core 而不是各应用 application.yml：迁移是底座能力，
 * 三个应用指向同一个库，配置项分散写三份迟早会漂移（改一处漏一处），
 * 三个应用的 @SpringBootApplication 都扫描 com.ai.workbench，这里天然生效。
 *
 * 两个关键设置：
 *
 * 1) locations = classpath:db/migration
 *    各模块把自己的迁移放在自己的 jar 里——platform-core 管 chat_memory（底座表），
 *    app-bank 管银行域表。Flyway 会把 classpath 上所有 jar 的该目录合并成一条时间线，
 *    版本号因此是跨模块全局递增的，不能各模块从 V1 重新数。
 *
 * 2) baselineOnMigrate + baselineVersion=0
 *    存量库（引入 Flyway 之前就存在、且表已经建好的库）里没有 flyway_schema_history，
 *    Flyway 默认会直接报「found non-empty schema without schema history table」拒绝启动。
 *    baselineVersion 取 0（低于所有迁移）而不是默认的 1，是刻意的：让 V1 起的每个迁移
 *    都在存量库上照样执行一遍。存量库的表结构参差不齐（实测开发库 ai_workbench 有
 *    platform_user.status 却没有 bank_transfer_order.executed_at），只有「全部迁移都跑」
 *    加上「迁移语句本身幂等」才能让任意历史状态的库收敛到同一结构。
 *    配套约束见迁移文件头部说明：V1/V2 用 CREATE TABLE IF NOT EXISTS，列变更走 V3 的存在性判断。
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer platformFlywayCustomizer() {
        return configuration -> configuration
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0");
    }
}
