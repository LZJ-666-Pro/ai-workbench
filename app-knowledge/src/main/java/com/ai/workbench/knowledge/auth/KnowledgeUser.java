package com.ai.workbench.knowledge.auth;

import com.ai.workbench.core.auth.CorePrincipal;

/**
 * 知识库服务的登录主体：本地实现 platform-core 的 CorePrincipal 接口。
 *
 * 为什么不直接复用 app-bank 的 JwtService.AuthPrincipal：app-* 模块之间互不依赖，
 * 依赖方向只允许 app-* → platform-core；各应用用自己的 record 实现同一接口，
 * 平台级功能（@RequestAttribute(CorePrincipal.ATTR_PRINCIPAL)）即可正常取到登录人。
 */
public record KnowledgeUser(String username, String displayName, String platformRole)
        implements CorePrincipal {
}
