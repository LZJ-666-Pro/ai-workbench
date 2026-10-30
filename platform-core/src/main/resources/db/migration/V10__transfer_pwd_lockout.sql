-- 支付密码试错锁定：同一确认单连续输错 3 次即作废。
--
-- 为什么挂列而不放内存：锁定状态必须和确认单同生共死——内存计数在重启后清零，
-- 等于给暴力枚举留了一扇「等服务重启」的门；落库才能保证「作废的单子永远确认不了」。
-- 计数与作废都在 TransferService.verifyPayPassword 的事务里推进。
ALTER TABLE bank_transfer_order
    ADD COLUMN pwd_fail_count INT NOT NULL DEFAULT 0;
