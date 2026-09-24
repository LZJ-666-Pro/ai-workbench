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
-- 定位为企业银行后台：除助手零售身份张三外，客户均为对公企业（8 开头账号）
INSERT IGNORE INTO bank_account (account_no, owner, balance) VALUES
    ('62220001', '张三', 12850.50),
    ('82280002', '远航贸易', 3420.00),
    ('82280003', '嘉禾制造', 98760.00);

-- 流水造数用显式主键保证幂等，自增从 11 继续
INSERT IGNORE INTO bank_transaction (id, account_no, amount, description) VALUES
    (1, '62220001',  18000.00, '2026-09-01 工资入账 +18000.00'),
    (2, '62220001',  -6500.00, '2026-09-05 房贷扣款 -6500.00'),
    (3, '62220001',  -3200.00, '2026-09-12 信用卡还款 -3200.00'),
    (4, '82280002',    500.00, '2026-09-03 手续费返还 +500.00'),
    (5, '82280002',   -286.50, '2026-09-10 办公费用 -286.50'),
    (6, '82280003',  50000.00, '2026-08-28 货款回账 +50000.00'),
    (7, '82280003',   -358.20, '2026-09-15 水电缴费 -358.20'),
    -- 期初余额：使 balance = 期初 + Σ流水，账实相符
    (8,  '62220001',  4550.50, '2026-08-31 期初余额 +4550.50'),
    (9,  '82280002',  3206.50, '2026-08-31 期初余额 +3206.50'),
    (10, '82280003', 49118.20, '2026-08-31 期初余额 +49118.20');

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

-- 对公客户演示账户（多服务对象）：对公账号按段位约定 8 开头（DbBankService.describeOwner）
INSERT IGNORE INTO bank_account (account_no, owner, balance) VALUES
    ('82280001', '星辰科技', 520000.00),
    ('82280004', '恒通物流', 50000.00),
    ('82280005', '云启数据', 30000.00);

-- 对公流水（显式主键幂等，id 从 101 起避开测试转账占用的自增段）：
-- balance 520000 = 期初 480000 + 88000 货款回款 - 11500 报销代发 + 3500 采购退款
INSERT IGNORE INTO bank_transaction (id, account_no, amount, description) VALUES
    (101, '82280001', 480000.00, '2026-08-31 期初余额 +480000.00'),
    (102, '82280001', 88000.00, '2026-09-08 XX贸易公司货款回账 +88000.00'),
    (103, '82280001', -11500.00, '2026-09-10 员工报销代发 -11500.00'),
    (104, '82280001', 3500.00, '2026-09-15 办公设备采购退款 +3500.00');

-- 平台登录用户（登录认证）：密码存 BCrypt 哈希；identity_id 关联银行助手身份
-- （BankIdentity.id），登录后前端用它生成 memoryId（bank:{identityId}:uuid），
-- 后端拦截器也用它校验请求里的 memoryId 归属，防止跨身份访问他人会话。
-- 演示账号种子由 PlatformUserSeeder 启动时写入（表空才插），密码统一 123456。
CREATE TABLE IF NOT EXISTS platform_user (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    platform_role VARCHAR(16)  NOT NULL DEFAULT 'USER',  -- ADMIN（可进管理后台）/ USER
    identity_id   VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

