package com.ai.workbench.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台数据服务（8080）：把「与具体 Agent 无关的平台能力」从 app-bank 独立成进程——
 * 登录认证、用户管理、工作台统计、平台监控、平台知识库与数据源管理接口。
 * 独立后 8081（银行助手）停止，登录与管理界面照常工作，平台不再随单一 Agent 存亡。
 *
 * 组件扫描根与 app-knowledge 一致（com.ai.workbench）：core 的服务 bean
 * （LlmConfig、TextIndexer、数据源服务等）+ 本模块 platform.* 的控制器与拦截器。
 * 8080 不装配任何 AgentSpec，AgentRegistry 为空表——工作台的「应用卡片」运行态
 * 由前端各应用自身的探活接口决定，不依赖本进程。
 */
@SpringBootApplication(scanBasePackages = "com.ai.workbench")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
