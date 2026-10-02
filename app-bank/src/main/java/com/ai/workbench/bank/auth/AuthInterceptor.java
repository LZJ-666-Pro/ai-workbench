package com.ai.workbench.bank.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.workbench.bank.auth.JwtService.AuthPrincipal;
import com.ai.workbench.bank.identity.BankIdentity;
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
 * 登录拦截器：除 /api/auth/login 外的所有 /api/** 都要求 Bearer token；
 * /api/admin/** 额外要求 ADMIN 角色；请求携带 memoryId 时校验其身份段与
 * 登录身份一致，防止 A 身份读写 B 身份的会话/转账确认单。
 * 每次请求回库核对账号状态：停用即时生效（不等 token 过期）。
 *
 * 注意适用范围：HandlerInterceptor 只能拦住走 RequestMappingHandlerMapping 的请求。
 * Actuator 端点由独立的 WebMvcEndpointHandlerMapping 处理，不经过这里——
 * 因此指标端点的鉴权由 {@link ActuatorAuthFilter} 承担，两者共用
 * {@link #authenticate} 这一份登录态校验，避免两处实现各写一套。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 登录主体在 request attribute 里的键名（Controller 可用 @RequestAttribute 取） */
    public static final String ATTR_PRINCIPAL = "authUser";

    /** 路径里带 memoryId 的接口：/api/memory/{agent}/{memoryId}、/api/sessions/{agent}/{memoryId} */
    private static final Pattern PATH_MEMORY_ID =
            Pattern.compile("^/api/(?:memory|sessions)/[^/]+/(.+)$");

    /** 对话流接口：/api/chat/{agent}/stream —— 必须显式携带 memoryId */
    private static final Pattern CHAT_STREAM = Pattern.compile("^/api/chat/[^/]+/stream$");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public AuthInterceptor(JwtService jwtService, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        AuthPrincipal principal = authenticate(request, response, path.startsWith("/api/admin/"));
        if (principal == null) {
            return false;
        }

        // memoryId 归属校验：参数形式（chat/stream）或路径形式（memory、sessions）
        String memoryId = request.getParameter("memoryId");
        if (memoryId == null) {
            Matcher m = PATH_MEMORY_ID.matcher(path);
            if (m.matches()) {
                memoryId = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            }
        }
        if (memoryId == null && CHAT_STREAM.matcher(path).matches()) {
            // memoryId 是身份的唯一载体：缺失时不但没有归属可校验，身份还会退化成 RETAIL，
            // 等于任何登录账号都能以零售客户（张三）的身份查账户、发转账。必须直接拒绝。
            return reject(response, 400, "缺少会话标识 memoryId");
        }
        if (memoryId != null && !memoryIdBelongsTo(principal.identityId(), memoryId)) {
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
     * 登录态校验（拦截器与 Actuator 过滤器共用）：
     * Bearer token → 验签 → 回库核对账号状态 →（可选）管理员角色。
     *
     * 失败时本方法已把错误响应写好，调用方只需在返回 null 时终止请求。
     * 抽出来共用是因为「账号被停用即时生效」这类规则一旦有两份实现，
     * 迟早出现只改了其中一处、另一条路径继续放行的漏洞。
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
        request.setAttribute(ATTR_PRINCIPAL, principal);

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

    /**
     * memoryId（bank:{identityId}:uuid）是否属于该登录身份；
     * 两段旧格式（bank:{uuid}）没有身份段，按 RETAIL 处理，仅 RETAIL 身份可用。
     *
     * 控制器也需要这个判断（请求体里的 memoryId 拦截器看不到），故开放为 public static 复用，
     * 避免两处实现各写一份、日后改一处漏一处。
     */
    public static boolean memoryIdBelongsTo(String identityId, String memoryId) {
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
