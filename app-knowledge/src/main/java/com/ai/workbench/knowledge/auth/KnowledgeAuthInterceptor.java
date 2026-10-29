package com.ai.workbench.knowledge.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.workbench.core.auth.CorePrincipal;
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
 * 知识库服务登录拦截器：/api/** 全部要求 Bearer token（登录入口在 app-bank，本服务无免认证接口）。
 *
 * 与 app-bank 的 AuthInterceptor 同一套规则的裁剪版：
 *  - 验签（jjwt 0.12.6）→ 回库核对 platform_user 状态（停用/删除即时生效，不等 token 过期）
 *    → request attribute 放入登录主体（Controller 用 @RequestAttribute 取）
 *  - 请求携带 memoryId 时校验归属：个人库的会话编码是 knowledge:{username}:{uuid}，
 *    username 段必须与 token 一致，防止 A 身份读写 B 身份的会话历史
 *
 * memoryId 校验的适用范围：聊天 SSE 是 GET /api/chat/knowledge/stream?memoryId=...（query 参数）；
 * 会话历史/删除是 /api/memory/{agent}/{memoryId}、/api/sessions/{agent}/{memoryId}（路径段）。
 * 个人库 REST 接口不携带 memoryId——此时只做 token 校验，不强求会话标识
 * （本服务所有接口都需要登录，没有匿名页）。
 */
@Component
public class KnowledgeAuthInterceptor implements HandlerInterceptor {

    /** 路径里带 memoryId 的接口：/api/memory/{agent}/{memoryId}、/api/sessions/{agent}/{memoryId} */
    private static final Pattern PATH_MEMORY_ID =
            Pattern.compile("^/api/(?:memory|sessions)/[^/]+/(.+)$");

    /** 个人库会话编码：knowledge:{username}:{uuid}，username 段不允许出现冒号 */
    private static final Pattern MEMORY_ID_FORMAT =
            Pattern.compile("^knowledge:([^:]+):[^:]+$");

    /** 管理后台路径前缀：企业治理面（跨用户库治理/绑定/审计），仅 ADMIN 可入 */
    private static final String ADMIN_PATH_PREFIX = "/api/kb/admin";

    private final KnowledgeJwtService jwtService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public KnowledgeAuthInterceptor(KnowledgeJwtService jwtService, ObjectMapper objectMapper,
                                    JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response, 401, "未登录或登录已过期");
        }
        KnowledgeUser user;
        try {
            user = jwtService.parse(header.substring(7));
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, 401, "登录已过期，请重新登录");
        }

        // 回库核对账号状态：token 未过期但账号被停用/删除时立即拒绝（与 app-bank 同规则）
        Integer status;
        try {
            status = jdbc.queryForObject(
                    "SELECT status FROM platform_user WHERE username = ?", Integer.class, user.username());
        } catch (EmptyResultDataAccessException e) {
            return reject(response, 401, "账号不存在或已被删除");
        }
        if (status == null || status != 1) {
            return reject(response, 403, "账号已被停用，请联系管理员");
        }

        request.setAttribute(CorePrincipal.ATTR_PRINCIPAL, user);

        // 管理后台是企业治理面：能看到并操作所有用户的个人库，普通用户即使登录也不可越权。
        // ADMIN 判定放在验签与账号状态核验之后——先确认「是谁、账号是否可用」，再谈「能做什么」；
        // role 取自 JWT claim（app-bank 签发），与 platform_user.platform_role 同源
        if (request.getRequestURI().startsWith(ADMIN_PATH_PREFIX)
                && !"ADMIN".equals(user.platformRole())) {
            return reject(response, 403, "需要管理员权限");
        }

        // memoryId 归属校验：query 参数（chat/stream）或路径段（memory、sessions）
        String memoryId = request.getParameter("memoryId");
        if (memoryId == null) {
            Matcher m = PATH_MEMORY_ID.matcher(request.getRequestURI());
            if (m.matches()) {
                memoryId = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            }
        }
        if (memoryId != null && !memoryIdBelongsTo(user.username(), memoryId)) {
            return reject(response, 403, "无权访问其他用户的会话");
        }
        // 会话列表按身份过滤参数（/api/sessions/knowledge?identity=xxx）：只允许查自己的
        String identity = request.getParameter("identity");
        if (identity != null && !identity.equals(user.username())) {
            return reject(response, 403, "无权查看其他用户的会话列表");
        }
        return true;
    }

    /**
     * memoryId（knowledge:{username}:{uuid}）是否属于该登录身份：
     * 三段且 username 段一致才算本人的会话。开放为 public static 是因为
     * 校验规则一旦有两份实现，迟早出现只改一处、另一条路径继续放行的漏洞。
     */
    public static boolean memoryIdBelongsTo(String username, String memoryId) {
        if (memoryId == null || username == null) {
            return false;
        }
        Matcher m = MEMORY_ID_FORMAT.matcher(memoryId);
        return m.matches() && m.group(1).equals(username);
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
