package com.ai.workbench.bank.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Actuator 端点鉴权（/actuator/**，探针 health 除外，要求 ADMIN）。
 *
 * 为什么必须用 Filter 而不是复用 WebConfig 里的 HandlerInterceptor：
 * WebMvcConfigurer.addInterceptors 注册的拦截器只会挂到 RequestMappingHandlerMapping 上，
 * 而 Actuator 端点由独立的 WebMvcEndpointHandlerMapping 处理，压根不经过那些拦截器。
 * 也就是说在 addPathPatterns 里写 "/actuator/**" 是**无效**的——实测 /actuator/metrics
 * 仍能被匿名访问并返回完整指标（能反映业务量级与错误率，属内部信息）。
 * Filter 在 DispatcherServlet 之前统一生效，与 HandlerMapping 无关，才是可靠的位置。
 *
 * 本类刻意不加 @Component：@Component 的 Filter 会被 Spring Boot 注册到 /*，
 * 那样连 /api/** 也会被重复鉴权一次。这里由 WebConfig 用 FilterRegistrationBean
 * 精确指定成 /actuator/*，作用范围一目了然。
 *
 * 健康检查放行的原因同 WebConfig：容器编排与负载均衡探针不带 token，
 * 若要求认证，readiness 探针永远失败，服务会被判定不可用而反复重启。
 */
public class ActuatorAuthFilter extends OncePerRequestFilter {

    private final AuthInterceptor authInterceptor;

    public ActuatorAuthFilter(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isProbePath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            // 复用拦截器那份登录态校验（验签 + 回库核对账号状态 + 管理员角色）
            if (authInterceptor.authenticate(request, response, true) == null) {
                return; // 失败响应已由 authenticate 写好，不再往下走
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"鉴权失败\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isProbePath(String path) {
        return "/actuator/health".equals(path) || path.startsWith("/actuator/health/");
    }
}
