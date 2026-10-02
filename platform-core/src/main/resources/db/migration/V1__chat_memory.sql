-- 会话记忆持久化（platform-core MysqlChatMemoryStore 使用）
--
-- 【迁移写法约定：用 CREATE TABLE IF NOT EXISTS 是刻意为之】
-- 本项目在引入 Flyway 之前靠「每次启动执行 classpath*:schema.sql」建表，存量库
-- （开发库 ai_workbench、测试库 ai_workbench_test）里这些表早已存在。配合
-- FlywayConfig 的 baselineOnMigrate + baselineVersion=0，存量库会把 V1 起的每个迁移
-- 完整执行一遍，只有幂等语句才能安全通过。详见 FlywayConfig 的类注释。
--
-- 迁移文件一旦提交就不再修改（Flyway 校验和会拒绝启动），后续结构变更一律新增 V{n}__*.sql。
CREATE TABLE IF NOT EXISTS chat_memory (
    memory_id  VARCHAR(190) PRIMARY KEY,
    content    MEDIUMTEXT   NOT NULL,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
