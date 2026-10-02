package com.ai.workbench.bank.config;

import com.ai.workbench.bank.auth.ActuatorAuthFilter;
import com.ai.workbench.bank.auth.AuthInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册登录拦截器 + Actuator 鉴权过滤器 + BCrypt 密码编码器。
 * 只引 spring-security-crypto 这一个类库做哈希，不启用 Spring Security 过滤器链。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注意：这里**不能**用 addPathPatterns("/actuator/**") 来保护指标端点。
        // 拦截器只作用于 RequestMappingHandlerMapping，而 Actuator 端点由
        // WebMvcEndpointHandlerMapping 处理，不经过拦截器——写了也不生效（实测匿名可访问）。
        // Actuator 的鉴权因此交给下面的 ActuatorAuthFilter，它基于 Filter，与 HandlerMapping 无关。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录接口本身免认证；/error 是 Spring 的错误转发，避免二次拦截掩盖真实状态码
                .excludePathPatterns("/api/auth/login", "/error");
    }

    /**
     * 指标端点鉴权：/actuator/** 要求 ADMIN 登录（探针 /actuator/health 由过滤器内部放行）。
     *
     * 顺序排在 TraceIdFilter（HIGHEST_PRECEDENCE）之后：这样鉴权失败写出的日志
     * 也带着本次请求的 traceId，才能和调用方拿到的编号对上。
     */
    @Bean
    public FilterRegistrationBean<ActuatorAuthFilter> actuatorAuthFilter() {
        FilterRegistrationBean<ActuatorAuthFilter> registration =
                new FilterRegistrationBean<>(new ActuatorAuthFilter(authInterceptor));
        registration.addUrlPatterns("/actuator/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("actuatorAuthFilter");
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
