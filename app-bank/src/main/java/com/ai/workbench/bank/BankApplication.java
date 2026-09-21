package com.ai.workbench.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 1 旗舰应用：带风控的银行交易 Agent。
 * 当前为 Phase 0 骨架：单 Agent + 查余额工具 + 流式对话。
 */
@SpringBootApplication(scanBasePackages = "com.ai.workbench")
public class BankApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankApplication.class, args);
    }
}
