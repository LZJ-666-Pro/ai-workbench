-- 审计表补 trace_id：把「哪一次请求」也记下来。
--
-- 背景：bank_audit_log 原本只有 memory_id（会话粒度）。同一个会话里连续提问多次，
-- 记录全混在一起，排查「某一次调用为什么失败」只能靠时间猜。
-- trace_id 是单次请求粒度，与日志里的 [traceId] 一一对应：从审计行拿到编号，
-- 就能在日志里拉出这次请求贯穿的全部行（含跑在 LLM 客户端线程上的那些）。
--
-- 这里可以直接写裸 ALTER，不像 V3 那样判存在：V3 之后的库都由 Flyway 接管，
-- 每个迁移保证只执行一次，「列是否已存在」不再需要自己判断——这正是引入迁移的价值。
-- 允许 NULL：历史数据与无请求上下文的调用（后台任务、直连测试）没有 trace_id。
ALTER TABLE bank_audit_log
    ADD COLUMN trace_id VARCHAR(32) NULL AFTER result,
    ADD INDEX idx_audit_trace (trace_id);
