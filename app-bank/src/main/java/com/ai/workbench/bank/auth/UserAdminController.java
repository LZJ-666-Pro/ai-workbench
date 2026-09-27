package com.ai.workbench.bank.auth;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.ai.workbench.bank.identity.BankIdentity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台用户管理（仅 ADMIN，路径经 AuthInterceptor 的角色校验）：
 *   GET    /api/admin/users                  用户列表
 *   POST   /api/admin/users                  新增用户（管理员开通制：账号由此入口落库）
 *   PATCH  /api/admin/users/{id}/status      启用 / 停用（停用自己的账号被拒绝）
 *   PUT    /api/admin/users/{id}/password    重置密码
 * 停用不删数据：审计留痕是企业系统的基本要求。
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    /** 前端表格展示用视图，不含密码哈希 */
    public record UserView(long id, String username, String displayName,
                           String platformRole, String identityId, boolean enabled, String createdAt) {
    }

    public record CreateUserRequest(String username, String password, String displayName,
                                    String platformRole, String identityId) {
    }

    public record StatusRequest(boolean enabled) {
    }

    public record PasswordRequest(String password) {
    }

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserAdminController(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @GetMapping
    public List<UserView> list() {
        return jdbc.query(
                "SELECT id, username, display_name, platform_role, identity_id, status, created_at "
                        + "FROM platform_user ORDER BY id",
                (rs, i) -> new UserView(rs.getLong("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("platform_role"),
                        rs.getString("identity_id"), rs.getInt("status") == 1,
                        rs.getString("created_at")));
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody CreateUserRequest req) {
        if (req == null || isBlank(req.username()) || isBlank(req.password()) || isBlank(req.displayName())) {
            return badRequest("用户名、密码、姓名均不能为空");
        }
        if (!USERNAME_PATTERN.matcher(req.username()).matches()) {
            return badRequest("用户名需为 3-32 位字母、数字或下划线");
        }
        if (req.password().length() < MIN_PASSWORD_LENGTH) {
            return badRequest("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (!"ADMIN".equals(req.platformRole()) && !"USER".equals(req.platformRole())) {
            return badRequest("平台角色无效");
        }
        if (findIdentity(req.identityId()) == null) {
            return badRequest("银行身份无效");
        }
        try {
            jdbc.update(
                    "INSERT INTO platform_user (username, password_hash, display_name, platform_role, identity_id) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    req.username(), encoder.encode(req.password()), req.displayName(),
                    req.platformRole(), req.identityId());
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(409).body(Map.of("message", "用户名已存在"));
        }
        return ResponseEntity.ok(Map.of("message", "用户 " + req.displayName() + " 已开通"));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> updateStatus(
            @PathVariable long id, @RequestBody StatusRequest req,
            @RequestAttribute(AuthInterceptor.ATTR_PRINCIPAL) JwtService.AuthPrincipal principal) {
        var usernames = jdbc.query(
                "SELECT username FROM platform_user WHERE id = ?",
                (rs, i) -> rs.getString("username"), id);
        if (usernames.isEmpty()) {
            return badRequest("用户不存在");
        }
        // 停用自己的账号会把当前管理员锁在系统外，直接拒绝
        if (!req.enabled() && usernames.getFirst().equals(principal.username())) {
            return badRequest("不能停用当前登录的账号");
        }
        jdbc.update("UPDATE platform_user SET status = ? WHERE id = ?", req.enabled() ? 1 : 0, id);
        return ResponseEntity.ok(Map.of("message", req.enabled() ? "账号已启用" : "账号已停用"));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @PathVariable long id, @RequestBody PasswordRequest req) {
        if (req == null || isBlank(req.password()) || req.password().length() < MIN_PASSWORD_LENGTH) {
            return badRequest("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        int updated = jdbc.update("UPDATE platform_user SET password_hash = ? WHERE id = ?",
                encoder.encode(req.password()), id);
        if (updated == 0) {
            return badRequest("用户不存在");
        }
        return ResponseEntity.ok(Map.of("message", "密码已重置"));
    }

    /** 校验 identityId 是否为合法的银行身份（BankIdentity 枚举值） */
    private BankIdentity findIdentity(String identityId) {
        if (identityId == null) {
            return null;
        }
        for (BankIdentity identity : BankIdentity.values()) {
            if (identity.id().equals(identityId)) {
                return identity;
            }
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
