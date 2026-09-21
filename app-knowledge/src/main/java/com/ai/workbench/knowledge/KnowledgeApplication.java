package com.ai.workbench.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 2 应用：多源知识库路由 Agent（学习笔记 / 代码仓库 / 网页收藏）。
 * 当前为 Phase 0 占位：纯对话，无检索。
 */
@SpringBootApplication(scanBasePackages = "com.ai.workbench")
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
