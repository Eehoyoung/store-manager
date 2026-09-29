package com.storemanager.api.security;

import com.storemanager.api.sysauth.SessionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization: Bearer {accessToken} 헤더를 검증해 SecurityContext 에 인증정보를 채운다.
 *
 * <p>클레임 없는 기존 토큰(USER)은 그대로 SESSION_USER 권한을 받는다. {@code st=ADMIN|HQ}
 * 토큰은 {@code sid} 로 Redis 세션이 실제로 살아 있는지 매 요청마다 확인한 뒤에만 각각
 * SESSION_ADMIN/SESSION_HQ 권한을 준다(docs/26a auth.session — 자동연장 없음, 세션이 없으면
 * 서명이 멀쩡해도 인증되지 않은 것으로 취급해 {@code anyRequest().authenticated()} 가 401 을 낸다).
 *
 * <p>principal 은 USER/HQ 는 사용자 public_id(UUID 문자열), ADMIN 은 이메일이다 — 관리자는
 * app_user 행이 없다(docs/26a decisions.admin).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final SessionService sessionService;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, SessionService sessionService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtTokenProvider.parseClaims(header.substring(7));
                String subject = claims.getSubject();
                String sessionType = claims.get("st", String.class);
                String sid = claims.get("sid", String.class);
                if (sessionType == null) {
                    setAuthentication(subject, "SESSION_USER");
                } else if ("ADMIN".equals(sessionType) && sessionService.exists(SessionService.Kind.ADMIN, sid)) {
                    setAuthentication(subject, "SESSION_ADMIN");
                } else if ("HQ".equals(sessionType) && sessionService.exists(SessionService.Kind.HQ, sid)) {
                    setAuthentication(subject, "SESSION_HQ");
                }
                // 그 외(세션이 없어졌거나 알 수 없는 st)는 인증을 세우지 않는다 — SecurityConfig 가 401 로 처리한다.
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private void setAuthentication(String principal, String authority) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
