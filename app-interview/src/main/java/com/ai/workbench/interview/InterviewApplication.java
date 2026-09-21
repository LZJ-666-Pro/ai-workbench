package com.ai.workbench.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 3 应用：AI 面试模拟器（简历解析 RAG + 面试官 Agent + 评估报告）。
 * 当前为 Phase 0 占位：已可多轮流式模拟面试。
 */
@SpringBootApplication(scanBasePackages = "com.ai.workbench")
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
