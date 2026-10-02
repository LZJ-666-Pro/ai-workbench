-- 平台工作台数据底座：跨应用运行日志 + 知识库/面试领域表。
--
-- 一、platform_event_log（平台运行日志）
-- 工作台首页与「查看日志」的统一数据源。为什么单独建表而不是复用 bank_audit_log：
--   - bank_audit_log 是银行业务域的合规审计（字段与语义为银行定制，只记业务工具调用），
--     消费者是管理后台的审计页与合规追溯；
--   - platform_event_log 是平台运行日志，形状跨应用统一，除工具调用外还记对话、
--     登录、护栏拒绝、知识库检索、面试轮次等非业务事件，消费者是工作台与运维。
--   两者维度不同，不能互相替代，因此 ToolAuditLogger 作为共同入口会各写一份。
--
-- 二、knowledge_document / knowledge_query_log / interview_session
-- 这三张是 app-knowledge、app-interview 的领域表，按分层本应写在各自模块的迁移里。
-- 当前这两个应用还没有任何持久化代码（只是占位对话），而工作台首页是平台级视图、
-- 需要跨应用统计，暂放底座统一管理；待两个应用落地后随代码一起迁回各自模块。
-- 注释在这里写清楚，避免日后变成"没人知道为什么在底座里"的表。

CREATE TABLE IF NOT EXISTS platform_event_log (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    app         VARCHAR(32)  NOT NULL,           -- bank / knowledge / interview / platform
    category    VARCHAR(32)  NOT NULL,           -- chat / tool / auth / guard / search / interview
    action      VARCHAR(64)  NOT NULL,           -- 具体动作，如 agent.chat、queryAccount
    actor       VARCHAR(64)  NULL,               -- 用户名（登录事件必有；对话事件可能没有）
    memory_id   VARCHAR(190) NULL,               -- 关联会话，便于与审计表/traceId 对齐
    detail      VARCHAR(512) NOT NULL,
    result      VARCHAR(16)  NOT NULL,           -- SUCCESS / DENY / FAIL
    duration_ms INT          NULL,               -- 端到端耗时（对话/检索类事件有值）
    tokens      INT          NULL,               -- token 用量（对话类事件有值）
    trace_id    VARCHAR(32)  NULL,               -- 与日志里的 [traceId] 一致，可反向回溯
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_app_time (app, created_at),
    INDEX idx_event_time (created_at),
    INDEX idx_event_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    source     VARCHAR(64)  NOT NULL,            -- 制度库 / 产品手册 / 监管文件 / 会议纪要
    doc_type   VARCHAR(16)  NOT NULL,            -- pdf / docx / md
    chunks     INT          NOT NULL DEFAULT 0,  -- 切分后的片段数（检索的最小单位）
    size_kb    INT          NOT NULL DEFAULT 0,
    embedded   TINYINT(1)   NOT NULL DEFAULT 0,  -- 是否已向量化（0 表示待入库）
    owner      VARCHAR(64)  NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_query_log (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    memory_id  VARCHAR(190) NULL,
    question   VARCHAR(255) NOT NULL,
    hits       INT          NOT NULL DEFAULT 0,  -- 命中片段数，0 表示没检索到
    latency_ms INT          NOT NULL,
    result     VARCHAR(16)  NOT NULL,            -- SUCCESS / FAIL（无命中记为 FAIL）
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_kq_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS interview_session (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    candidate   VARCHAR(64) NOT NULL,
    position    VARCHAR(64) NOT NULL,
    rounds      INT         NOT NULL DEFAULT 0,
    score       INT         NULL,                -- 结构化评分（0-100），未结束为 NULL
    status      VARCHAR(16) NOT NULL,            -- FINISHED / ONGOING
    started_at  TIMESTAMP   NOT NULL,
    finished_at TIMESTAMP   NULL,
    INDEX idx_iv_started (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
