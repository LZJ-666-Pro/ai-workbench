package com.ai.workbench.core.auth;

/**
 * 平台级登录主体抽象：让 platform-core 的平台功能（如数据源管理）能声明
 * 「当前登录用户」参数，而不必反向依赖某个具体应用的 JWT 实现。
 *
 * 依赖方向是 app-* → platform-core，因此这里只定义各应用都该提供的最小契约
 * （登录用户名）；token 解析、角色校验、停用核对等仍由各应用的拦截器负责。
 *
 * 实现方：app-bank 的 JwtService.AuthPrincipal（record 组件 username()
 * 天然满足接口方法），由 AuthInterceptor 在请求进入时放入 request attribute。
 */
public interface CorePrincipal {

    /** request attribute 的键名（平台层与各应用拦截器共用同一份，避免两处字符串漂移） */
    String ATTR_PRINCIPAL = "authUser";

    /** 登录用户名（platform_user 表主键之一） */
    String username();
}
