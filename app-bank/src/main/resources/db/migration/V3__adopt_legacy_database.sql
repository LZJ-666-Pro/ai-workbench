-- 存量库结构对齐：把「引入 Flyway 之前建的老库」补齐到与 V1/V2 一致的结构。
--
-- 背景：老库的列变更是靠启动时查 information_schema 再 ALTER 的补丁类做的
-- （原 BankSchemaUpgrader、PlatformUserSeeder.ensureStatusColumn）。那些类已随本次
-- 治理删除，补列逻辑收敛到这里，成为有版本号、有执行记录、可回放的一步。
--
-- 为什么必须判存在（而不是直接 ALTER）：本迁移在两类库上都会执行——
--   1) 全新库 / 测试库：V2 建表时已经带上了这两列，直接 ALTER 会报 Duplicate column name；
--   2) 存量老库：列可能缺失（实测开发库 ai_workbench 就有 platform_user.status
--      却没有 bank_transfer_order.executed_at——两处补丁类跑过的历史不同）。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，只能查 information_schema 后动态拼 DDL。
-- 这也正是「结构状态无法自证」的代价：V3 之后所有变更都走正常迁移，不再需要这种判断。

-- platform_user.status：早期版本没有 status 列（停用后登录与已签发 token 即时失效）
SET @has_status := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_user' AND COLUMN_NAME = 'status');
SET @ddl := IF(@has_status = 0,
    'ALTER TABLE platform_user ADD COLUMN status TINYINT(1) NOT NULL DEFAULT 1 AFTER identity_id',
    'SELECT 1');
PREPARE adopt_stmt FROM @ddl;
EXECUTE adopt_stmt;
DEALLOCATE PREPARE adopt_stmt;

-- bank_transfer_order.executed_at：日限额改按「资金真正划转的时刻」归集时新增
SET @has_executed_at := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bank_transfer_order' AND COLUMN_NAME = 'executed_at');
SET @ddl := IF(@has_executed_at = 0,
    'ALTER TABLE bank_transfer_order ADD COLUMN executed_at TIMESTAMP NULL AFTER notified',
    'SELECT 1');
PREPARE adopt_stmt FROM @ddl;
EXECUTE adopt_stmt;
DEALLOCATE PREPARE adopt_stmt;

-- 历史 EXECUTED 单没有执行时刻，用创建时间回填：否则日累计会漏掉这些单子，限额被凭空放宽
UPDATE bank_transfer_order SET executed_at = created_at
WHERE status = 'EXECUTED' AND executed_at IS NULL;
