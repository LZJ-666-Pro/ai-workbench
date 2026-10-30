package com.ai.workbench.platform.api;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ai.workbench.core.config.RagProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台监控接口：给 /monitor 监控页一次性返回「真实探测」结果。
 *
 * 为什么每项都真探而不是返回配置好的静态状态：监控页的价值就在「现在到底通不通」，
 * 静态状态与"配置了什么"是两回事——库停了、端口不通，页面必须红而不是绿。
 *
 * 探测成本刻意收敛：MySQL 用现成连接池 SELECT 1；向量库只做 TCP 端口连通
 * （真实网络级探测，不产生 embedding 调用费用）；LLM 只查配置是否在位
 * （真调一次对话接口要花钱且慢，配置检查 + 应用内实际调用由对话链路本身验证）。
 *
 * 挂在 /api/admin/ 前缀下：鉴权拦截器对该前缀统一要求 ADMIN，鉴权只写一处。
 */
@RestController
@RequestMapping("/api/admin/platform-monitor")
public class PlatformMonitorController {

    private final JdbcTemplate jdbc;
    private final RagProperties rag;

    @Value("${ai.llm.base-url:}")
    private String llmBaseUrl;

    @Value("${ai.llm.api-key:}")
    private String llmApiKey;

    @Value("${ai.llm.model-name:}")
    private String llmModel;

    public PlatformMonitorController(JdbcTemplate jdbc, RagProperties rag) {
        this.jdbc = jdbc;
        this.rag = rag;
    }

    /** 单项健康检查结果：status 取 UP / DOWN / DISABLED / UNCONFIGURED */
    public record Check(String key, String name, String status, Long latencyMs, String detail) {
    }

    @GetMapping
    public Map<String, Object> monitor() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checks", checks());
        result.put("runtime", runtime());
        result.put("todayStats", todayStats());
        result.put("trend24h", trend24h());
        return result;
    }

    private List<Check> checks() {
        List<Check> checks = new ArrayList<>();

        // MySQL：连接池现成连接跑 SELECT 1，同时验证连通性与鉴权
        checks.add(timed("mysql", "MySQL 数据库", () -> {
            jdbc.execute("SELECT 1");
            return "连接正常";
        }));

        // pgvector：TCP 连通探测（端口通 = 服务在；列级问题由检索链路自身暴露）
        if (!rag.isEnabled()) {
            checks.add(new Check("vector", "pgvector 向量库", "DISABLED", null,
                    "向量检索未启用，知识库自动退化为纯关键词召回"));
        } else {
            final String host = rag.getHost();
            final int port = rag.getPort();
            checks.add(timed("vector", "pgvector 向量库", () -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 1500);
                    return host + ":" + port + " 端口连通";
                }
            }));
        }

        // LLM：配置级检查。不回传 key 本身，只回报「在不在位」
        if (llmApiKey == null || llmApiKey.isBlank()) {
            checks.add(new Check("llm", "LLM 模型服务", "UNCONFIGURED", null,
                    "未检测到 AI_LLM_API_KEY，对话与检索增强不可用"));
        } else {
            checks.add(new Check("llm", "LLM 模型服务", "UP", null,
                    "已配置（" + shortHost(llmBaseUrl) + " · " + llmModel + "）"));
        }
        return checks;
    }

    /** 把一段探测逻辑包装成带耗时的 Check；任何异常都翻译成 DOWN 而不是 500 */
    private Check timed(String key, String name, Probe probe) {
        long start = System.nanoTime();
        try {
            String detail = probe.run();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Check(key, name, "UP", ms, detail);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Check(key, name, "DOWN", ms, reason);
        }
    }

    @FunctionalInterface
    private interface Probe {
        String run() throws Exception;
    }

    private static String shortHost(String url) {
        if (url == null || url.isBlank()) {
            return "未知端点";
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private Map<String, Object> runtime() {
        Runtime rt = Runtime.getRuntime();
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        runtime.put("heapUsedMb", memory.getUsed() / 1024 / 1024);
        runtime.put("heapMaxMb", memory.getMax() / 1024 / 1024);
        runtime.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        runtime.put("processors", rt.availableProcessors());
        runtime.put("javaVersion", System.getProperty("java.version"));
        return runtime;
    }

    private Map<String, Object> todayStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("events", countWhere(" AND 1=1"));
        stats.put("fails", countWhere(" AND result = 'FAIL'"));
        stats.put("denies", countWhere(" AND result = 'DENY'"));
        return stats;
    }

    private long countWhere(String extra) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_event_log
                WHERE created_at >= CURDATE()""" + extra, Long.class);
        return n == null ? 0 : n;
    }

    /** 近 24 小时事件趋势：按整点分桶（UNIX_TIMESTAMP/3600），从当前小时回填 24 桶，缺桶补零 */
    private List<Map<String, Object>> trend24h() {
        Map<Long, Long> byBucket = new LinkedHashMap<>();
        jdbc.query("""
                SELECT (UNIX_TIMESTAMP(created_at) DIV 3600) AS bucket, COUNT(*) AS n
                FROM platform_event_log
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                GROUP BY bucket
                """, rs -> {
            byBucket.put(rs.getLong("bucket"), rs.getLong("n"));
        });
        long currentBucket = System.currentTimeMillis() / 3_600_000;
        List<Map<String, Object>> trend = new ArrayList<>();
        for (long i = 23; i >= 0; i--) {
            long bucket = currentBucket - i;
            String label = java.time.Instant.ofEpochSecond(bucket * 3600)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:00"));
            trend.add(Map.of("hour", label, "count", byBucket.getOrDefault(bucket, 0L)));
        }
        return trend;
    }
}
