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
        return createAccessToken(userPublicId, null, null, accessTtlSeconds);
    }

    /**
     * 시스템 관리자·가맹본부 담당자 세션용 토큰 (docs/26a auth.jwtClaim).
     * {@code sessionType} 이 null 이면 클레임 없는 기존 USER 토큰과 동일한 모양이 된다.
     * {@code st} 는 세션 종류, {@code sid} 는 Redis 서버 세션 키다 — 둘 다 있어야
     * {@link com.storemanager.api.security.JwtAuthFilter} 가 ADMIN/HQ 권한을 부여한다.
     */
    public String createAccessToken(String subject, String sessionType, String sid, long ttlSeconds) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (sessionType != null) {
            builder.claim("st", sessionType);
        }
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.signWith(key).compact();
    }

    /** 회전 방식 Refresh 토큰용 랜덤 문자열. Redis 에 rt:{token} 키로 저장한다. */
    public String createRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /** 토큰을 검증하고 subject(사용자 public_id)를 반환한다. 유효하지 않으면 JwtException. */
    public String parseSubject(String token) {
        return parseClaims(token).getSubject();
    }

    /** 토큰을 검증하고 전체 클레임을 반환한다(st/sid 포함). 유효하지 않으면 JwtException. */
    public Claims parseClaims(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }
}
