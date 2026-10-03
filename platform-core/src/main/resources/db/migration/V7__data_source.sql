-- 数据源管理：平台级的数据连接登记表。
--
-- 定位：数据源是「登记 + 探活」而不是「代理访问」——平台在此登记各 Agent 背后
-- 依赖的文档库 / 数据库 / API / 向量索引，提供连通性测试与绑定关系视图；
-- 真正的读取仍由各应用自己的代码完成。这也决定了表里没有凭据加密列：
-- 演示环境凭据随 config 存 JSON（返回前端时 password 字段脱敏）。
--
-- 与 V6 的知识库表的关系：knowledge_document 是知识库应用的内容清单，
-- data_source 是平台视角的连接登记（哪个库、被哪些 Agent 绑定、通不通），维度不同。

CREATE TABLE IF NOT EXISTS data_source (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(16)  NOT NULL,            -- DOCUMENT / DATABASE / API / VECTOR
    engine          VARCHAR(24)  NOT NULL,            -- 子类型：mysql / postgresql / pgvector / http / oss / file
    description     VARCHAR(255) NULL,
    config          TEXT         NULL,                -- JSON：连接配置（host/port/url/password 等，按 type 而异）
    status          VARCHAR(16)  NOT NULL DEFAULT 'UNTESTED', -- OK / ERROR / SYNCING / UNTESTED
    status_msg      VARCHAR(255) NULL,                -- 最近一次探活的结论（异常原因 / 版本号等）
    last_sync_at    TIMESTAMP    NULL,                -- 最近一次同步 / 刷新时间
    last_latency_ms INT          NULL,                -- 最近一次测试连接的耗时（基础监控用）
    owner           VARCHAR(64)  NOT NULL,            -- 登记人（登录用户名）
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ds_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 数据源 ↔ Agent 绑定关系（多对多）：回答「这个数据源被哪些 Agent 使用」
CREATE TABLE IF NOT EXISTS data_source_binding (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    source_id  BIGINT      NOT NULL,
    agent_key  VARCHAR(32) NOT NULL,               -- bank / knowledge / interview
    scope      VARCHAR(16) NOT NULL DEFAULT 'READ', -- 数据权限范围：READ / WRITE
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ds_binding (source_id, agent_key),
    KEY idx_ds_binding_agent (agent_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
