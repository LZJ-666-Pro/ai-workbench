-- 电子回单：转账执行成功时生成银行回单号，支撑「查看电子回单」与回单查询工具。
--
-- 回单是资金变动的凭证，与状态机的 CAS 推进写在同一条 UPDATE 里落库：
-- 状态不是 EXECUTED 就绝不会有 bank_ref，杜绝「单子还没执行却查得到回单」的假凭证。
-- 允许 NULL：PENDING / CANCELLED / REJECTED 单没有回单。
-- 唯一索引：回单号必须全局唯一（MySQL 唯一索引允许多行 NULL，不影响未执行单）。
ALTER TABLE bank_transfer_order
    ADD COLUMN bank_ref VARCHAR(32) NULL AFTER status,
    ADD UNIQUE INDEX uk_transfer_bank_ref (bank_ref);
