package com.ai.workbench.platform.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探活接口（免认证）：前端监控页用 GET /platform/api/platform/ping 判断
 * 平台数据服务（8080）是否在线。只回答「进程活着」，不带业务语义——
 * 与各 Agent 应用探测 /api/chat/agents 的语义不同（那边探的是 Agent 就绪态）。
 */
@RestController
public class PingController {

    @GetMapping("/api/platform/ping")
    public Map<String, Object> ping() {
        return Map.of("service", "app-platform", "status", "UP");
    }
}
