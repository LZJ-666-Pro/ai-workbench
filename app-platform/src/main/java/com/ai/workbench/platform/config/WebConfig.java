package com.ai.workbench.platform.config;

import com.ai.workbench.platform.auth.PlatformAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 平台数据服务的 Web 配置：登录拦截器 + BCrypt 密码编码器。
 * 与 app-bank 的 WebConfig 同构；本服务 management 只开 health/info，
 * 没有 /actuator/metrics 这类敏感端点，故无需 app-bank 的 ActuatorAuthFilter。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PlatformAuthInterceptor authInterceptor;

    public WebConfig(PlatformAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录接口与探活 ping 本身免认证；/error 是 Spring 的错误转发，
                // 避免二次拦截掩盖真实状态码
                .excludePathPatterns("/api/auth/login", "/api/platform/ping", "/error");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
