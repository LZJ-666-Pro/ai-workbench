package com.ai.workbench.bank.auth;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录认证接口：
 *   POST /api/auth/login  用户名密码换 JWT（唯一免认证接口）
 *   GET  /api/auth/me     用 token 换当前用户信息（前端刷新页面后还原登录态）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    /** 前端展示用的用户视图（不含密码），identityId 用于生成 memoryId 与身份渲染 */
    public record UserView(String username, String displayName, String platformRole, String identityId) {
    }

    public record LoginResponse(String token, UserView user) {
    }

    private record UserRow(String username, String passwordHash, String displayName,
                           String platformRole, String identityId) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || isBlank(req.username()) || isBlank(req.password())) {
            return badRequest("请输入用户名和密码");
        }
        var rows = jdbc.query(
                "SELECT username, password_hash, display_name, platform_role, identity_id "
                        + "FROM platform_user WHERE username = ?",
                (rs, i) -> new UserRow(rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("platform_role"),
                        rs.getString("identity_id")),
                req.username());
        if (rows.isEmpty() || !encoder.matches(req.password(), rows.getFirst().passwordHash())) {
            return ResponseEntity.status(401).body(Map.of("message", "用户名或密码错误"));
        }
        UserRow u = rows.getFirst();
        String token = jwtService.issue(u.username(), u.displayName(), u.platformRole(), u.identityId());
        return ResponseEntity.ok(new LoginResponse(token,
                new UserView(u.username(), u.displayName(), u.platformRole(), u.identityId())));
    }

    @GetMapping("/me")
    public UserView me(@RequestAttribute(AuthInterceptor.ATTR_PRINCIPAL) JwtService.AuthPrincipal p) {
        return new UserView(p.username(), p.displayName(), p.role(), p.identityId());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
