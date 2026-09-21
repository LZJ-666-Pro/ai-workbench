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
