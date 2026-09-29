package com.storemanager.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.sysauth.SessionService;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JwtAuthFilter — st(세션종류) 클레임에 따른 권한 부여와 ADMIN/HQ 의 Redis 세션 재확인(docs/26a auth.session).
 */
class JwtAuthFilterTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            "test-secret-value-for-jwt-auth-filter-unit-test-32bytes", 1800, 1209600);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 클레임이_없는_기존_토큰은_SESSION_USER_권한을_받는다() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        String publicId = UUID.randomUUID().toString();
        String token = provider.createAccessToken(publicId);

        run(new JwtAuthFilter(provider, sessionService), token);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(publicId);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("SESSION_USER");
    }

    @Test
    void ADMIN_토큰은_세션이_살아있어야_권한을_받는다() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        String token = provider.createAccessToken("boss@sodam.test", "ADMIN", "sid-1", 600);
        when(sessionService.exists(SessionService.Kind.ADMIN, "sid-1")).thenReturn(true);

        run(new JwtAuthFilter(provider, sessionService), token);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo("boss@sodam.test");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("SESSION_ADMIN");
    }

    /** ★ 세션이 지워졌으면(담당자 접근 중지 등) 서명이 멀쩡해도 인증을 세우지 않는다. */
    @Test
    void ADMIN_토큰이어도_세션이_없으면_인증되지_않는다() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        String token = provider.createAccessToken("boss@sodam.test", "ADMIN", "sid-revoked", 600);
        when(sessionService.exists(SessionService.Kind.ADMIN, "sid-revoked")).thenReturn(false);

        run(new JwtAuthFilter(provider, sessionService), token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void HQ_토큰은_세션이_살아있어야_권한을_받는다() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        String publicId = UUID.randomUUID().toString();
        String token = provider.createAccessToken(publicId, "HQ", "sid-hq", 28800);
        when(sessionService.exists(SessionService.Kind.HQ, "sid-hq")).thenReturn(true);

        run(new JwtAuthFilter(provider, sessionService), token);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("SESSION_HQ");
    }

    @Test
    void 위조된_토큰은_인증되지_않는다() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        String token = provider.createAccessToken(UUID.randomUUID().toString()) + "tampered";

        run(new JwtAuthFilter(provider, sessionService), token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void run(JwtAuthFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
    }
}
