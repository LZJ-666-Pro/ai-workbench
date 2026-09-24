package com.ai.workbench.bank.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.workbench.bank.identity.BankIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器：除 /api/auth/login 外的所有 /api/** 都要求 Bearer token；
 * /api/admin/** 额外要求 ADMIN 角色；请求携带 memoryId 时校验其身份段与
 * 登录身份一致，防止 A 身份读写 B 身份的会话/转账确认单。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 登录主体在 request attribute 里的键名（Controller 可用 @RequestAttribute 取） */
    public static final String ATTR_PRINCIPAL = "authUser";

    /** 路径里带 memoryId 的接口：/api/memory/{agent}/{memoryId}、/api/sessions/{agent}/{memoryId} */
    private static final Pattern PATH_MEMORY_ID =
            Pattern.compile("^/api/(?:memory|sessions)/[^/]+/(.+)$");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, 401, "未登录或登录已过期");
        }
        JwtService.AuthPrincipal principal;
        try {
            principal = jwtService.parse(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, 401, "登录已过期，请重新登录");
        }
        request.setAttribute(ATTR_PRINCIPAL, principal);

        String path = request.getRequestURI();
        // 管理后台接口仅 ADMIN 可访问
        if (path.startsWith("/api/admin/") && !"ADMIN".equals(principal.role())) {
            return reject(response, 403, "需要管理员权限");
        }

        // memoryId 归属校验：参数形式（chat/stream）或路径形式（memory、sessions）
        String memoryId = request.getParameter("memoryId");
        if (memoryId == null) {
            Matcher m = PATH_MEMORY_ID.matcher(path);
            if (m.matches()) {
                memoryId = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            }
        }
        if (memoryId != null && !memoryIdAllowed(principal.identityId(), memoryId)) {
            return reject(response, 403, "无权访问其他身份的会话");
        }
        // 会话列表按身份过滤参数：只允许查自己的
        String identity = request.getParameter("identity");
        if (identity != null && !identity.equals(principal.identityId())) {
            return reject(response, 403, "无权查看其他身份的会话列表");
        }
        return true;
    }

    /**
     * memoryId（bank:{identityId}:uuid）必须属于登录身份；
     * 两段旧格式（bank:{uuid}）没有身份段，按 RETAIL 处理，仅 RETAIL 身份可用。
     */
    private boolean memoryIdAllowed(String identityId, String memoryId) {
        String[] parts = memoryId.split(":");
        return switch (parts.length) {
            case 3 -> parts[1].equals(identityId);
            case 2 -> BankIdentity.RETAIL.id().equals(identityId);
            default -> false;
        };
    }

    /** 统一返回 JSON 错误体，前端按 message 展示 */
    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(java.util.Map.of("message", message)));
        return false;
    }
}
