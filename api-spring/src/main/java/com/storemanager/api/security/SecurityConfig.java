package com.storemanager.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.common.ErrorResponse;
import com.storemanager.api.naver.ExtensionAuthService;
import com.storemanager.api.naver.ExtensionTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Stateless JWT 인증. /auth/**, actuator health, swagger 만 공개하고 나머지는 인증을 요구한다. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    /** 쉼표 구분. 기본값은 로컬 Vite dev 서버뿐이다. 운영 도메인은 환경변수로 주입한다. */
    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS — 웹(Vite dev 5173)이 다른 오리진에서 API 를 호출하기 위해 필요하다.
     * ★ 허용 오리진을 와일드카드로 열지 않는다. refresh 토큰이 HttpOnly 쿠키라
     * allowCredentials=true 가 필요하고, 이 조합에서 "*" 는 스펙상 금지될 뿐 아니라
     * 임의 사이트가 로그인된 사장님 세션으로 API 를 호출할 수 있게 된다.
     * 운영 도메인은 APP_CORS_ORIGINS 환경변수로 주입한다.
     *
     * <p>★ 네이버 확장(extension/)의 chrome-extension:// 오리진은 여기 넣지 않는다. 확장은
     * service worker/확장 페이지에서 fetch 하고 manifest.json 의 host_permissions 로 접근을
     * 허가받으므로 애초에 브라우저 CORS 검사 대상이 아니다(IMPLEMENTATION_PLAN_NAVER.md §6).
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(java.util.List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Extension-Token — 네이버 크롬 확장 인증 헤더(IMPLEMENTATION_PLAN_NAVER.md §6).
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Extension-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        // ★ /internal/** 은 제외한다. 워커 전용 경로이며 브라우저에서 호출될 일이 없다.
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }

    /**
     * ★ ExtensionAuthService 는 생성자가 아니라 이 @Bean 메서드의 파라미터로 받는다.
     * ExtensionAuthService → PasswordEncoder(=이 클래스의 @Bean) 인데, 생성자 주입으로 받으면
     * "SecurityConfig 를 만들려면 ExtensionAuthService 가 먼저 있어야 하고, 그건 아직 만들다 만
     * SecurityConfig 의 passwordEncoder() 를 필요로 한다" 는 순환이 생겨 기동이 실패한다
     * (BeanCurrentlyInCreationException, 통합테스트로 실측). 메서드 파라미터로 받으면 SecurityConfig
     * 싱글턴이 먼저 완성된 뒤에 필요한 시점에 조회하므로 순환이 끊긴다.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ExtensionAuthService extensionAuthService)
            throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ★ /legal/business-info 는 거래 전에 보여야 하는 표시사항이라 로그인 뒤로 숨기지 않는다
                        //   (전자상거래법 제10조). 사업자 등록부에 공개된 항목만 실린다.
                        .requestMatchers("/api/v1/auth/**", "/api/v1/agreements",
                                "/api/v1/agreements/documents/**", "/api/v1/legal/business-info",
                                "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        // 페어링 코드 → 확장 토큰 교환은 아직 로그인 수단이 없는 확장이 부르므로 무인증이다.
                        // 코드 자체가 5분 TTL 1회용이라(ExtensionAuthService) 무인증이어도 안전하다.
                        .requestMatchers("/api/v1/naver/extension/pair").permitAll()
                        // /internal/** 는 JWT 가 아니라 X-Internal-Token 공유 시크릿으로 인증한다(컨트롤러에서 검증).
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(this::handleUnauthorized)
                        .accessDeniedHandler(this::handleForbidden))
                .addFilterBefore(new JwtAuthFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ExtensionTokenFilter(extensionAuthService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void handleUnauthorized(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        writeError(res, ErrorCode.UNAUTHORIZED);
    }

    private void handleForbidden(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
            throws IOException {
        writeError(res, ErrorCode.FORBIDDEN);
    }

    private void writeError(HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.getStatus().value());
        res.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = new ErrorResponse(code.name(), code.getMessage(),
                UUID.randomUUID().toString().substring(0, 8), null);
        res.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
