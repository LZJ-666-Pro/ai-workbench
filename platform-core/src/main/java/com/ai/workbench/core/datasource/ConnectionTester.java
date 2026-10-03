package com.ai.workbench.core.datasource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 数据源连通性测试：按类型对目标做一次真实探测，返回耗时与结论。
 *
 * 为什么是「真连一次」而不是拼个假状态：数据源页面的价值就在于状态列可信——
 * 数据库真的握手、API 真的发请求、文件路径真的查存在性。探活失败时把异常
 * 摘要写进 status_msg，用户不用翻日志就能看到"连不通"的原因。
 *
 * 三类探测的超时都压在 3 秒：测试连接是人工操作，宁可报超时也不要让用户等。
 */
@Component
public class ConnectionTester {

    /** 探测结论：ok=连通；latencyMs=本次探测耗时；message=成功描述或失败原因 */
    public record TestResult(boolean ok, int latencyMs, String message) {
        public static TestResult of(boolean ok, long start, String message) {
            return new TestResult(ok, (int) Math.min(System.currentTimeMillis() - start, Integer.MAX_VALUE), message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public TestResult test(String type, String engine, Map<String, Object> config) {
        long start = System.currentTimeMillis();
        try {
            String message = switch (type) {
                case "DATABASE", "VECTOR" -> testJdbc(engine, config);
                case "API" -> testHttp(str(config, "url"), false);
                case "DOCUMENT" -> testDocument(config);
                default -> throw new IllegalArgumentException("不支持的数据源类型：" + type);
            };
            return TestResult.of(true, start, message);
        } catch (Exception e) {
            return TestResult.of(false, start, brief(e));
        }
    }

    /** 数据库 / 向量库：拼 JDBC URL 真连一次，再跑 SELECT 1 确认可执行查询 */
    private String testJdbc(String engine, Map<String, Object> config) throws Exception {
        String host = required(config, "host");
        String database = required(config, "database");
        String port = String.valueOf(config.get("port"));
        String url = switch (engine) {
            // MySQL 的 connectTimeout 单位是毫秒；PostgreSQL 是秒
            case "mysql" -> "jdbc:mysql://%s:%s/%s?connectTimeout=3000&socketTimeout=3000"
                    .formatted(host, port, database);
            case "postgresql", "pgvector" -> "jdbc:postgresql://%s:%s/%s?connectTimeout=3&socketTimeout=3"
                    .formatted(host, port, database);
            default -> throw new IllegalArgumentException("不支持的数据库类型：" + engine);
        };
        DriverManager.setLoginTimeout(3);
        try (Connection conn = DriverManager.getConnection(url,
                str(config, "username"), str(config, "password"))) {
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
            String version = conn.getMetaData().getDatabaseProductVersion();
            return "连接成功（" + (version != null && version.length() > 60
                    ? version.substring(0, 60) : version) + "）";
        }
    }

    /** API：GET 一次，能收到响应即服务可达（4xx 说明服务在，只是鉴权/参数问题） */
    private String testHttp(String url, boolean headOnly) throws Exception {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL 必须以 http:// 或 https:// 开头");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3));
        if (headOnly) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        HttpResponse<Void> resp = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        int code = resp.statusCode();
        if (code >= 500) {
            throw new IllegalStateException("服务异常，HTTP " + code);
        }
        return (headOnly ? "存储可达" : "服务可达") + "，HTTP " + code;
    }

    /** 文档/文件：OSS 等 http(s) 地址发 HEAD；本地路径查存在性与可读性 */
    private String testDocument(Map<String, Object> config) throws Exception {
        String location = required(config, "location");
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return testHttp(location, true);
        }
        Path path = Path.of(location);
        if (!Files.exists(path)) {
            throw new java.nio.file.NoSuchFileException("路径不存在：" + location);
        }
        if (!Files.isReadable(path)) {
            throw new AccessDeniedException(location);
        }
        return Files.isRegularFile(path) ? "文件可读，" + Files.size(path) + " 字节" : "目录可读";
    }

    private String required(Map<String, Object> config, String key) {
        String value = str(config, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填配置项：" + key);
        }
        return value;
    }

    private String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 异常摘要：类名 + 首行信息，截到 180 字符（status_msg 列宽 255，留余量） */
    private String brief(Exception e) {
        String text = e.getClass().getSimpleName();
        if (e.getMessage() != null) {
            text += "：" + e.getMessage();
        }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }
}
