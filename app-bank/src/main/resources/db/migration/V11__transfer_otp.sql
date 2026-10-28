-- 短信验证码二次因子：用户确认转账时除支付密码外还需输入短信验证码（双因子）。
-- 码随订单落库而不是放内存：服务重启不丢码，验证与失败计数能和状态机在同一事务里推进。
-- 演示环境无短信通道，码由 HTTP 响应直接回显（见 TransferService.sendOtp）；
-- 生产应改为对接短信网关、库中仅存哈希，且验证码不进入任何日志。
ALTER TABLE bank_transfer_order
    ADD COLUMN otp_code VARCHAR(6) NULL AFTER pwd_fail_count,
    ADD COLUMN otp_expire_at DATETIME NULL AFTER otp_code,
    ADD COLUMN otp_fail_count INT NOT NULL DEFAULT 0 AFTER otp_expire_at,
    ADD COLUMN otp_sent_at DATETIME NULL AFTER otp_fail_count;
