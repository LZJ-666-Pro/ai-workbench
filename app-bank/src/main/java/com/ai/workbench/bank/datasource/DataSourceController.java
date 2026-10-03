package com.ai.workbench.bank.datasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ai.workbench.bank.auth.AuthInterceptor;
import com.ai.workbench.bank.auth.JwtService.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源管理（登录即可用；状态与绑定关系是全员可见的平台视图）：
 *   GET    /api/datasources          列表（含绑定关系，密码脱敏）
 *   POST   /api/datasources          新建登记
 *   PUT    /api/datasources/{id}     编辑（密码留空 = 沿用原值）
 *   DELETE /api/datasources/{id}     删除（级联清绑定关系）
 *   POST   /api/datasources/{id}/test  测试连接：真实探活并回写状态
 *   POST   /api/datasources/{id}/sync  触发同步：刷新最近同步时间
 *
 * 连接凭据随 config 存 JSON。列表与详情返回时把 password 抹成 "******"；
 * 编辑回传空密码表示"不改"，避免把脱敏后的星号存回库里。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    public record BindingView(String agentKey, String scope) {
    }

    public record DataSourceView(long id, String name, String type, String engine, String description,
                                 Map<String, Object> config, String status, String statusMsg,
                                 String lastSyncAt, Integer lastLatencyMs, String owner,
                                 String createdAt, List<BindingView> bindings) {
    }

    public record SaveRequest(String name, String type, String engine, String description,
                              Map<String, Object> config, List<BindingView> bindings) {
    }

    public record TestResponse(boolean ok, int latencyMs, String message, String status) {
    }

    private static final List<String> TYPES = List.of("DOCUMENT", "DATABASE", "API", "VECTOR");
    private static final List<String> ENGINES = List.of(
            "mysql", "postgresql", "pgvector", "http", "oss", "file");
    private static final List<String> AGENT_KEYS = List.of("bank", "knowledge", "interview");
    private static final List<String> SCOPES = List.of("READ", "WRITE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ConnectionTester tester;

    public DataSourceController(JdbcTemplate jdbc, ObjectMapper objectMapper, ConnectionTester tester) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tester = tester;
    }

    // ---------- 查询 ----------

    @GetMapping
    public List<DataSourceView> list() {
        Map<Long, List<BindingView>> bindings = new LinkedHashMap<>();
        jdbc.query(
                "SELECT source_id, agent_key, scope FROM data_source_binding ORDER BY id",
                rs -> {
                    bindings.computeIfAbsent(rs.getLong("source_id"), k -> new ArrayList<>())
                            .add(new BindingView(rs.getString("agent_key"), rs.getString("scope")));
                });
        return jdbc.query(
                "SELECT id, name, type, engine, description, config, status, status_msg, "
                        + "last_sync_at, last_latency_ms, owner, created_at FROM data_source ORDER BY id",
                (rs, i) -> new DataSourceView(
                        rs.getLong("id"), rs.getString("name"), rs.getString("type"),
                        rs.getString("engine"), rs.getString("description"),
                        sanitized(rs.getString("config")), rs.getString("status"),
                        rs.getString("status_msg"),
                        rs.getString("last_sync_at"), (Integer) rs.getObject("last_latency_ms"),
                        rs.getString("owner"), rs.getString("created_at"),
                        bindings.getOrDefault(rs.getLong("id"), List.of())));
    }

    // ---------- 新建 / 编辑 / 删除 ----------

    @PostMapping
    public ResponseEntity<Map<String, String>> create(
            @RequestBody SaveRequest req,
            @RequestAttribute(AuthInterceptor.ATTR_PRINCIPAL) AuthPrincipal principal) {
        String error = validate(req);
        if (error != null) {
            return badRequest(error);
        }
        try {
            jdbc.update("""
                    INSERT INTO data_source (name, type, engine, description, config, status, owner)
                    VALUES (?, ?, ?, ?, ?, 'UNTESTED', ?)
                    """, req.name(), req.type(), req.engine(), req.description(),
                    toJson(req.config()), principal.username());
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", "已存在同名数据源"));
        }
        Long id = jdbc.queryForObject("SELECT id FROM data_source WHERE name = ?", Long.class, req.name());
        saveBindings(id, req.bindings());
        return ResponseEntity.ok(Map.of("message", "数据源已登记，建议先「测试连接」确认连通性"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, String>> update(@PathVariable long id, @RequestBody SaveRequest req) {
        String error = validate(req);
        if (error != null) {
            return badRequest(error);
        }
        Map<String, Object> old = findConfig(id);
        if (old == null) {
            return badRequest("数据源不存在");
        }
        // 密码留空 = 不修改：前端拿到的本来就是脱敏后的星号，不能让它覆盖真值
        Map<String, Object> config = new LinkedHashMap<>(req.config() == null ? Map.of() : req.config());
        Object password = config.get("password");
        if (password == null || String.valueOf(password).isBlank()) {
            Object oldPassword = old.get("password");
            if (oldPassword != null) {
                config.put("password", oldPassword);
            } else {
                config.remove("password");
            }
        }
        int updated = jdbc.update("""
                UPDATE data_source SET name = ?, type = ?, engine = ?, description = ?, config = ?
                WHERE id = ?
                """, req.name(), req.type(), req.engine(), req.description(), toJson(config), id);
        if (updated == 0) {
            return badRequest("数据源不存在");
        }
        jdbc.update("DELETE FROM data_source_binding WHERE source_id = ?", id);
        saveBindings(id, req.bindings());
        return ResponseEntity.ok(Map.of("message", "数据源已更新；配置有变化时请重新测试连接"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable long id) {
        int deleted = jdbc.update("DELETE FROM data_source WHERE id = ?", id);
        if (deleted == 0) {
            return badRequest("数据源不存在");
        }
        jdbc.update("DELETE FROM data_source_binding WHERE source_id = ?", id);
        return ResponseEntity.ok(Map.of("message", "数据源已删除"));
    }

    // ---------- 测试连接 / 同步 ----------

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable long id) {
        List<DataSourceView> rows = jdbc.query(
                "SELECT id, name, type, engine, description, config, status, status_msg, "
                        + "last_sync_at, last_latency_ms, owner, created_at FROM data_source WHERE id = ?",
                (rs, i) -> new DataSourceView(rs.getLong("id"), rs.getString("name"), rs.getString("type"),
                        rs.getString("engine"), rs.getString("description"),
                        raw(rs.getString("config")), rs.getString("status"), rs.getString("status_msg"),
                        rs.getString("last_sync_at"), (Integer) rs.getObject("last_latency_ms"),
                        rs.getString("owner"), rs.getString("created_at"), List.of()), id);
        if (rows.isEmpty()) {
            return badRequest("数据源不存在");
        }
        DataSourceView source = rows.getFirst();
        ConnectionTester.TestResult result = tester.test(source.type(), source.engine(), source.config());
        String status = result.ok() ? "OK" : "ERROR";
        jdbc.update("UPDATE data_source SET status = ?, status_msg = ?, last_latency_ms = ? WHERE id = ?",
                status, result.message(), result.latencyMs(), id);
        return ResponseEntity.ok(new TestResponse(result.ok(), result.latencyMs(), result.message(), status));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, String>> sync(@PathVariable long id) {
        // 登记层没有真实数据通道，同步动作=刷新最近同步时间；连通与否以「测试连接」为准
        int updated = jdbc.update(
                "UPDATE data_source SET last_sync_at = CURRENT_TIMESTAMP, "
                        + "status_msg = IF(status = 'OK', '手动触发同步完成', status_msg) WHERE id = ?", id);
        if (updated == 0) {
            return badRequest("数据源不存在");
        }
        return ResponseEntity.ok(Map.of("message", "同步已触发，最近同步时间已刷新"));
    }

    // ---------- 校验与辅助 ----------

    private String validate(SaveRequest req) {
        if (req == null || isBlank(req.name())) {
            return "数据源名称不能为空";
        }
        if (req.name().length() > 100) {
            return "数据源名称不能超过 100 字";
        }
        if (!TYPES.contains(req.type())) {
            return "数据源类型无效";
        }
        if (!ENGINES.contains(req.engine())) {
            return "数据源子类型无效";
        }
        if (req.bindings() != null) {
            for (BindingView binding : req.bindings()) {
                if (!AGENT_KEYS.contains(binding.agentKey())) {
                    return "绑定的 Agent 无效";
                }
                if (!SCOPES.contains(binding.scope())) {
                    return "数据权限范围无效";
                }
            }
        }
        return null;
    }

    private void saveBindings(Long id, List<BindingView> bindings) {
        if (id == null || bindings == null || bindings.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT IGNORE INTO data_source_binding (source_id, agent_key, scope) VALUES (?, ?, ?)",
                bindings.stream()
                        .map(b -> new Object[]{id, b.agentKey(), b.scope()})
                        .toList());
    }

    private Map<String, Object> findConfig(long id) {
        List<String> rows = jdbc.query("SELECT config FROM data_source WHERE id = ?",
                (rs, i) -> rs.getString("config"), id);
        if (rows.isEmpty()) {
            return null;
        }
        return raw(rows.getFirst());
    }

    /** 库里的 config JSON → Map（测试连接用原始值） */
    private Map<String, Object> raw(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 返回前端的 config：密码抹成星号（存在与否保留布尔语义） */
    private Map<String, Object> sanitized(String json) {
        Map<String, Object> config = raw(json);
        Object password = config.get("password");
        if (password != null && !String.valueOf(password).isBlank()) {
            config.put("password", "******");
        }
        return config;
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
