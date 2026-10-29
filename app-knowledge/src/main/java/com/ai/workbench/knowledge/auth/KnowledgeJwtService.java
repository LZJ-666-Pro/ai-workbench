package com.ai.workbench.knowledge.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 验签（jjwt 0.12.x，HS256）。与 app-bank 的 JwtService 同密钥、同算法——
 * token 统一由 app-bank 的 /api/auth/login 签发，本服务只负责验签还原登录人，
 * 因此这里只保留 parse 一个方法；issue 留在签发方，避免两处都能发 token。
 *
 * claims 约定（与 app-bank 的 issue 一致）：subject=username、displayName、role。
 */
@Component
public class KnowledgeJwtService {

    private final SecretKey key;

    public KnowledgeJwtService(@Value("${auth.jwt.secret}") String secret) {
        // HS256 要求密钥至少 32 字节，不足会直接抛异常启动失败（尽早暴露配置问题）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析并验签；过期或伪造抛 JwtException，由拦截器统一转 401 */
    public KnowledgeUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new KnowledgeUser(
                c.getSubject(),
                c.get("displayName", String.class),
                c.get("role", String.class));
    }
}
