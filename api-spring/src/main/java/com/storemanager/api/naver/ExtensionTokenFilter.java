package com.storemanager.api.naver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Extension-Token 헤더를 검증해 JwtAuthFilter 와 동일한 형태의 Authentication 을 세팅한다.
 * principal 은 사용자 public_id(UUID 문자열) 하나뿐이다 — CurrentUser.publicId() 가 그대로 재사용된다.
 *
 * <p>★ 헤더가 없거나 토큰이 유효하지 않으면 아무것도 하지 않고 통과시킨다. JWT 경로에 전혀 영향을
 * 주지 않는다 — 이 필터가 하는 일은 SecurityContext 를 "채우는" 것뿐이지 "거부"가 아니다.
 * 인증이 실제로 필요한지는 SecurityConfig 의 authorizeHttpRequests 가 그 다음에 판단한다.
 */
public class ExtensionTokenFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Extension-Token";

    private final ExtensionAuthService extensionAuthService;

    public ExtensionTokenFilter(ExtensionAuthService extensionAuthService) {
        this.extensionAuthService = extensionAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token != null && !token.isBlank()) {
            String userPublicId = extensionAuthService.resolve(token);
            if (userPublicId != null) {
                var auth = new UsernamePasswordAuthenticationToken(userPublicId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
