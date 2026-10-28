-- 平台级知识库（kb = knowledge base space）。
--
-- 定位与边界：数据源管「连接和原始数据」，知识库管「把数据变成可检索的知识」，
-- 应用中心管「Agent 绑定哪些知识库」。V6 的 knowledge_document 是知识库应用
-- 自己的语料清单（无空间维度），本迁移建的是平台管理的多空间模型，两者并存、
-- 语义不同；应用侧接入空间模型后 knowledge_document 再逐步归并。
--
-- MVP 真实链路：上传 txt/md → 提取纯文本 → 按 chunk_size/overlap 切片 →
-- kb_chunk 落库 → 检索测试对切片做中文 bigram 关键词召回打分。
-- pdf/docx 允许上传但解析如实标 FAILED（文本提取引擎第二期接入）；
-- 向量语义检索待 embedding 接入后在 kb_chunk 上加 embedding 列即可平滑升级。

CREATE TABLE IF NOT EXISTS kb_space (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(255) NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'BUILDING', -- READY / BUILDING / FAILED
    chunk_size    INT          NOT NULL DEFAULT 500,        -- 高级配置：切片目标长度（字符）
    chunk_overlap INT          NOT NULL DEFAULT 50,         -- 高级配置：相邻切片重叠长度
    doc_count     INT          NOT NULL DEFAULT 0,          -- 冗余计数：列表页免聚合
    chunk_total   INT          NOT NULL DEFAULT 0,
    owner         VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS kb_document (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    space_id   BIGINT       NOT NULL,
    title      VARCHAR(200) NOT NULL,                 -- 文件名（去扩展名前保留原名）
    doc_type   VARCHAR(16)  NOT NULL,                 -- txt / md / csv / pdf / docx
    size_kb    INT          NOT NULL DEFAULT 0,
    words      INT          NOT NULL DEFAULT 0,       -- 提取出的总字数
    chunks     INT          NOT NULL DEFAULT 0,       -- 实际切片数
    status     VARCHAR(16)  NOT NULL DEFAULT 'PARSING', -- READY / PARSING / FAILED
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,       -- 启用/禁用（禁用不参与检索）
    content    MEDIUMTEXT   NULL,                     -- 提取的纯文本（重建索引的数据来源）
    error_msg  VARCHAR(255) NULL,
    owner      VARCHAR(64)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kbdoc_space (space_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 切片是检索的最小单位：检索测试按 chunk 打分返回，将来加 embedding 列即成向量索引
CREATE TABLE IF NOT EXISTS kb_chunk (
    id        BIGINT      AUTO_INCREMENT PRIMARY KEY,
    space_id  BIGINT      NOT NULL,
    doc_id    BIGINT      NOT NULL,
    seq       INT         NOT NULL,                  -- 文档内切片序号（从 0 开始）
    content   TEXT        NOT NULL,
    words     INT         NOT NULL DEFAULT 0,
    INDEX idx_kbchunk_space (space_id),
    INDEX idx_kbchunk_doc (doc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 知识库 ↔ Agent 绑定（哪些 Agent 具备这个空间的知识能力）
CREATE TABLE IF NOT EXISTS kb_binding (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    space_id   BIGINT      NOT NULL,
    agent_key  VARCHAR(32) NOT NULL,                 -- bank / knowledge / interview
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_binding (space_id, agent_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
