package com.ai.workbench.knowledge.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.ai.workbench.core.auth.CorePrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录拦截器纯单测：不启 Spring 容器、连 MockMvc 都不用——拦截器只依赖
 * HttpServletRequest/JwtService/JdbcTemplate，直接 new 出来对 Mock 请求断言最直接。
 * DB 依赖用 Mockito 桩掉 queryForObject；token 在测试内用同 secret 自签。
 */
class KnowledgeAuthInterceptorTest {

    private static final String SECRET = "ai-workbench-dev-jwt-secret-key-0123456789abcdef";

    private KnowledgeJwtService jwtService;
    private JdbcTemplate jdbc;
    private KnowledgeAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = new KnowledgeJwtService(SECRET);
        // JdbcTemplate 非接口，Mockito 5 默认 inline mock maker 可直接桩掉
        jdbc = mock(JdbcTemplate.class);
        interceptor = new KnowledgeAuthInterceptor(jwtService, new com.fasterxml.jackson.databind.ObjectMapper(), jdbc);
    }

    /** 用与 KnowledgeJwtService 相同的密钥自签 token（模拟 app-bank 签发） */
    private String tokenFor(String username) {
        return tokenFor(username, "USER");
    }

    /** role 可指定：管理后台的 ADMIN 判定读的就是这个 claim */
    private String tokenFor(String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("displayName", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/kb/personal/me");
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    @Test
    void noTokenRejected401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(request(null), response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("未登录");
    }

    @Test
    void forgedTokenRejected401() throws Exception {
        // 用错误密钥签的 token：验签不过，等同伪造
        String forged = Jwts.builder()
                .subject("alice")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor("another-secret-key-0123456789abcdef0123".getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(request(forged), response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unknownAccountRejected401() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("ghost")))
                .thenThrow(new EmptyResultDataAccessException(1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(request(tokenFor("ghost")), response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void disabledAccountRejected403() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(0);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(request(tokenFor("alice")), response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void foreignMemoryIdRejected403() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletRequest req = request(tokenFor("alice"));
        req.setParameter("memoryId", "knowledge:bob:session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(req, response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void malformedMemoryIdRejected403() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletRequest req = request(tokenFor("alice"));
        req.setParameter("memoryId", "not-a-valid-memory-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void validRequestPassesAndSetsPrincipal() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletRequest req = request(tokenFor("alice"));
        req.setParameter("memoryId", "knowledge:alice:session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(req, response, new Object());
        assertThat(pass).isTrue();
        Object principal = req.getAttribute(CorePrincipal.ATTR_PRINCIPAL);
        assertThat(principal).isInstanceOf(KnowledgeUser.class);
        assertThat(((KnowledgeUser) principal).username()).isEqualTo("alice");
    }

    @Test
    void restWithoutMemoryIdPasses() throws Exception {
        // 个人库 REST 接口不带 memoryId：只做 token + 账号状态校验
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(request(tokenFor("alice")), response, new Object());
        assertThat(pass).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void memoryIdBelongsToRules() {
        assertThat(KnowledgeAuthInterceptor.memoryIdBelongsTo("alice", "knowledge:alice:u1")).isTrue();
        assertThat(KnowledgeAuthInterceptor.memoryIdBelongsTo("alice", "knowledge:bob:u1")).isFalse();
        assertThat(KnowledgeAuthInterceptor.memoryIdBelongsTo("alice", "knowledge:alice")).isFalse();
        assertThat(KnowledgeAuthInterceptor.memoryIdBelongsTo("alice", null)).isFalse();
    }

    @Test
    void adminPathRejectedForNonAdmin() throws Exception {
        // 登录有效但角色是 USER：管理后台是企业治理面，登录态不等于管理权
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/kb/admin/overview");
        req.addHeader("Authorization", "Bearer " + tokenFor("alice", "USER"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(req, response, new Object());
        assertThat(pass).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("需要管理员权限");
    }

    @Test
    void adminPathAllowsAdminRole() throws Exception {
        // ADMIN 角色在 /api/kb/admin 前缀下正常放行，principal 照常放入
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("admin"))).thenReturn(1);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/kb/admin/overview");
        req.addHeader("Authorization", "Bearer " + tokenFor("admin", "ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(req, response, new Object());
        assertThat(pass).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(req.getAttribute(CorePrincipal.ATTR_PRINCIPAL)).isInstanceOf(KnowledgeUser.class);
    }

    @Test
    void nonAdminPathStillOpenToNormalUser() throws Exception {
        // 对照组：同样 USER 角色，非 /api/kb/admin 路径不受新判定影响
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("alice"))).thenReturn(1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request(tokenFor("alice", "USER")), response, new Object())).isTrue();
    }
}
