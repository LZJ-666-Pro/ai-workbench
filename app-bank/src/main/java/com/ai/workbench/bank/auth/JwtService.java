package com.ai.workbench.bank.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与验签（jjwt 0.12.x，HS256）。
 * 登录成功后签发 token，claims 里带上银行身份 identityId，
 * 后续请求由 AuthInterceptor 验签并还原出登录主体。
 */
@Component
public class JwtService {

    /** 验签后的登录主体：拦截器解析 token 后放进 request attribute */
    public record AuthPrincipal(String username, String displayName, String role, String identityId) {
    }

    private final SecretKey key;
    private final long expireMs;

    public JwtService(@Value("${auth.jwt.secret}") String secret,
                      @Value("${auth.jwt.expire-hours:72}") long expireHours) {
        // HS256 要求密钥至少 32 字节，不足会直接抛异常启动失败（尽早暴露配置问题）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMs = expireHours * 3600_000L;
    }

    public String issue(String username, String displayName, String role, String identityId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("displayName", displayName)
                .claim("role", role)
                .claim("identityId", identityId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expireMs)))
                .signWith(key)
                .compact();
    }

    /** 解析并验签；过期或伪造抛 JwtException，由调用方统一转 401 */
    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new AuthPrincipal(
                c.getSubject(),
                c.get("displayName", String.class),
                c.get("role", String.class),
                c.get("identityId", String.class));
    }
}
