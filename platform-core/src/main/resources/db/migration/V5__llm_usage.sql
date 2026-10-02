-- LLM 用量台账：成本护栏的数据基础。
--
-- 为什么落库而不是只留在内存指标里：
--   1) 限额要跨进程重启存活。内存计数器一重启就归零，护栏形同虚设；
--   2) 「服务对象当日用了多少」必须能事后审计——客户投诉「为什么给我限了」时，
--      要能拿出具体数字，而不是只有一条 Grafana 曲线；
--   3) 指标是按 model 聚合的（用于看整体），台账是按主体聚合的（用于算配额），
--      两者维度不同，不能互相替代。
--
-- usage_scope 是「计费主体」而不是会话：memoryId 形如 bank:{identityId}:{uuid}，
-- 去掉最后一段会话 id 得到 bank:{identityId}。若按会话限额，用户新开一个会话就重置，
-- 限额等于没有。
CREATE TABLE IF NOT EXISTS llm_usage (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    usage_scope   VARCHAR(190) NOT NULL,            -- 计费/限额主体（memoryId 去掉会话段）
    memory_id     VARCHAR(190) NOT NULL,            -- 具体会话，便于下钻
    agent         VARCHAR(64)  NOT NULL,
    model         VARCHAR(128) NOT NULL,
    input_tokens  INT          NOT NULL DEFAULT 0,  -- 上下文成本
    output_tokens INT          NOT NULL DEFAULT 0,  -- 生成成本（与输入单价不同，分列存）
    trace_id      VARCHAR(32)  NULL,                -- 与日志/审计表对齐
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_usage_scope_time (usage_scope, created_at),
    INDEX idx_usage_memory (memory_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
