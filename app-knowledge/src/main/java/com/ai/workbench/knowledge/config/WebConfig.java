package com.ai.workbench.knowledge.config;

import com.ai.workbench.knowledge.auth.KnowledgeAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册登录拦截器。/api/** 全部要求登录（本服务没有匿名页）；
 * /error 是 Spring 的错误转发，排除避免二次拦截掩盖真实状态码。
 *
 * 注意：Actuator 端点不走 RequestMappingHandlerMapping，拦截器管不到它——
 * 本服务只暴露不敏感的 health/info（见 application.yml），无需额外过滤器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final KnowledgeAuthInterceptor authInterceptor;

    public WebConfig(KnowledgeAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/error");
    }
}
