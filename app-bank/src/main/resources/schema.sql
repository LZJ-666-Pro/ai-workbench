-- 银行域表（Phase 1 Step 1：账户数据从内存迁到 MySQL）
-- 本文件随 classpath*:schema.sql 在每次启动时执行，故全部用幂等语句。

CREATE TABLE IF NOT EXISTS bank_account (
    account_no VARCHAR(32)  PRIMARY KEY,
    owner      VARCHAR(64)  NOT NULL,
    balance    DECIMAL(12,2) NOT NULL DEFAULT 0.00
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- amount 带符号：正数入账、负数支出，Phase 1 Step 2 转账流水直接复用
CREATE TABLE IF NOT EXISTS bank_transaction (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    account_no  VARCHAR(32)  NOT NULL,
    amount      DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    description VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bank_txn_account (account_no, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 造数：INSERT IGNORE 保证重启不重复
INSERT IGNORE INTO bank_account (account_no, owner, balance) VALUES
    ('62220001', '张三', 12850.50),
    ('62220002', '李四', 3420.00),
    ('62220003', '王五', 98760.00);

-- 流水造数用显式主键保证幂等，自增从 11 继续
INSERT IGNORE INTO bank_transaction (id, account_no, amount, description) VALUES
    (1, '62220001',  18000.00, '2026-09-01 工资入账 +18000.00'),
    (2, '62220001',  -6500.00, '2026-09-05 房贷扣款 -6500.00'),
    (3, '62220001',  -3200.00, '2026-09-12 信用卡还款 -3200.00'),
    (4, '62220002',    500.00, '2026-09-03 转账存入 +500.00'),
    (5, '62220002',   -286.50, '2026-09-10 超市消费 -286.50'),
    (6, '62220003',  50000.00, '2026-08-28 理财赎回 +50000.00'),
    (7, '62220003',   -358.20, '2026-09-15 水电缴费 -358.20'),
    -- 期初余额：使 balance = 期初 + Σ流水，账实相符
    (8,  '62220001',  4550.50, '2026-08-31 期初余额 +4550.50'),
    (9,  '62220002',  3206.50, '2026-08-31 期初余额 +3206.50'),
    (10, '62220003', 49118.20, '2026-08-31 期初余额 +49118.20');

-- 转账订单（Step 3 两段式）：模型只能创建 PENDING 确认单；只有确认接口的
-- 状态机 CAS（WHERE status='PENDING'）才能推进到 EXECUTED，重复提交幂等拦截
CREATE TABLE IF NOT EXISTS bank_transfer_order (
    id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    confirm_id   CHAR(36)      NOT NULL,
    memory_id    VARCHAR(190)  NOT NULL,
    from_account VARCHAR(32)   NOT NULL,
    to_account   VARCHAR(32)   NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    reason       VARCHAR(255)  NULL,
    status       VARCHAR(16)   NOT NULL,           -- PENDING / EXECUTED / CANCELLED / REJECTED
    notified     TINYINT(1)    NOT NULL DEFAULT 0, -- 确认卡片是否已推送给前端
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_confirm_id (confirm_id),
    INDEX idx_transfer_session (memory_id, status, notified),
    INDEX idx_transfer_from (from_account, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- AI 工具调用审计：谁（memoryId）、何时、调了什么、参数、结果（成功/拒绝/异常全量留痕）
CREATE TABLE IF NOT EXISTS bank_audit_log (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    memory_id  VARCHAR(190) NOT NULL,
    tool_name  VARCHAR(64)  NOT NULL,
    detail     VARCHAR(512) NOT NULL,
    result     VARCHAR(16)  NOT NULL,             -- SUCCESS / DENY / FAIL / ALLOW
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 风控黑名单演示账户（见 TransferRiskRules.BLACKLIST）
INSERT IGNORE INTO bank_account (account_no, owner, balance) VALUES
    ('62220004', '赵六', 100.00);
