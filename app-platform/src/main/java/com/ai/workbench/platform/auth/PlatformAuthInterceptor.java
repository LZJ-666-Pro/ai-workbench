package com.ai.workbench.platform.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.ai.workbench.core.auth.CorePrincipal;
import com.ai.workbench.core.auth.JwtService;
import com.ai.workbench.core.auth.JwtService.AuthPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 平台数据服务的登录拦截器：除 /api/auth/login 与 /api/platform/ping 外，
 * 所有 /api/** 都要求 Bearer token；/api/admin/** 额外要求 ADMIN 角色。
 *
 * 与 app-bank 的 AuthInterceptor 同构但职责更窄：平台接口（用户管理、数据源、
 * 平台知识库、工作台统计）没有会话/身份归属概念，不做 memoryId 校验。
 * 每次请求回库核对账号状态：停用即时生效（不等 token 过期）。
 */
@Component
public class PlatformAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public PlatformAuthInterceptor(JwtService jwtService, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        AuthPrincipal principal = authenticate(request, response, path.startsWith("/api/admin/"));
        return principal != null;
    }

    /**
     * 登录态校验：Bearer token → 验签 → 回库核对账号状态 →（可选）管理员角色。
     * 失败时本方法已把错误响应写好，调用方只需在返回 null 时终止请求。
     */
    public AuthPrincipal authenticate(HttpServletRequest request, HttpServletResponse response,
                                      boolean requireAdmin) throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, 401, "未登录或登录已过期");
            return null;
        }
        AuthPrincipal principal;
        try {
            principal = jwtService.parse(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, 401, "登录已过期，请重新登录");
            return null;
        }
        request.setAttribute(CorePrincipal.ATTR_PRINCIPAL, principal);

        // 回库核对账号状态：token 未过期但账号被停用/删除时立即拒绝
        Integer status;
        try {
            status = jdbc.queryForObject(
                    "SELECT status FROM platform_user WHERE username = ?", Integer.class, principal.username());
        } catch (EmptyResultDataAccessException e) {
            reject(response, 401, "账号不存在或已被删除");
            return null;
        }
        if (status == null || status != 1) {
            reject(response, 403, "账号已被停用，请联系管理员");
            return null;
        }
        if (requireAdmin && !"ADMIN".equals(principal.role())) {
            reject(response, 403, "需要管理员权限");
            return null;
        }
        return principal;
    }

    /** 统一返回 JSON 错误体，前端按 message 展示 */
    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("message", message)));
        return false;
    }
}
