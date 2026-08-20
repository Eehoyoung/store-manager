package com.storemanager.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access 토큰(JWT) 발급/검증과 Refresh 토큰(랜덤 문자열) 생성을 담당한다.
 * Access 토큰의 subject 는 사용자 public_id(UUID) 이며, 내부 BIGSERIAL id 는 절대 담지 않는다.
 * Refresh 토큰은 Redis 회전 저장 방식으로 AuthService 가 관리한다.
 */
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl-seconds}") long accessTtlSeconds,
            @Value("${app.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String createAccessToken(String userPublicId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userPublicId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /** 회전 방식 Refresh 토큰용 랜덤 문자열. Redis 에 rt:{token} 키로 저장한다. */
    public String createRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /** 토큰을 검증하고 subject(사용자 public_id)를 반환한다. 유효하지 않으면 JwtException. */
    public String parseSubject(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload().getSubject();
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }
}
