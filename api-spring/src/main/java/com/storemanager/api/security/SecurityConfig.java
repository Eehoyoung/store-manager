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

    /**
     * 네이버 확장의 chrome-extension:// 오리진. 쉼표 구분.
     *
     * <p>★ 개발 기본값이 와일드카드인 이유 — 압축해제 로드한 확장의 ID 는 설치 경로에서
     * 파생돼 기기마다 다르다. 운영에서는 웹스토어가 준 고정 ID 를 환경변수로 박는다
     * ({@code APP_CORS_ALLOWED_EXTENSION_ORIGINS=chrome-extension://<고정ID>}).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.cors.allowed-extension-origins:chrome-extension://*}")
    private String allowedExtensionOrigins;

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
     * <p>★ 확장이 CORS 대상이 아니라는 것은 <b>틀린 가정이었다</b>(실측 2026-09-20).
     * host_permissions 는 <b>브라우저 쪽</b> 검사를 면제할 뿐, 크롬은 여전히
     * {@code Origin: chrome-extension://<id>} 를 붙여 보낸다. 서버는 host_permissions 를
     * 알 도리가 없으므로 Spring CorsFilter 가 "Invalid CORS request" 로 403 을 낸다.
     * 페어링이 통째로 막혀 있었다. 그래서 {@code /api/v1/naver/**} 는 따로 등록한다.
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
        // ★ 등록 순서가 곧 우선순위다(첫 매치 승). 좁은 패턴을 먼저 넣는다.
        //   네이버 경로는 확장 오리진을 함께 허용해야 하므로 /api/v1/** 보다 앞이다.
        source.registerCorsConfiguration("/api/v1/naver/**", naverCorsConfiguration());
        // ★ /internal/** 은 제외한다. 워커 전용 경로이며 브라우저에서 호출될 일이 없다.
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }

    /**
     * {@code /api/v1/naver/**} 전용 CORS — 웹 대시보드 오리진에 확장 오리진을 더한다.
     *
     * <p>★ 경로를 쪼개지 않고 이 경로 전체에 확장 오리진을 허용하는 이유는
     * {@code /naver/status} 를 웹과 확장이 <b>둘 다</b> 부르기 때문이다.
     *
     * <p>★ allowCredentials 는 true 로 둔다. 웹 클라이언트가 모든 호출에
     * {@code credentials:"include"} 를 붙이기 때문이다(web/src/api/client.ts).
     * 그래도 이 경로에는 <b>주변권한(ambient authority)이 없다</b> — 유일한 쿠키인
     * refresh 토큰이 {@code path=/api/v1/auth} 라 여기로는 전송되지 않는다.
     * 인증은 {@code Authorization: Bearer} 와 {@code X-Extension-Token} 헤더로만 이뤄지고,
     * 둘 다 적대적 오리진이 CORS 로 가져갈 수 없는 값이다.
     *
     * <p>★ 그래도 {@code "*"} 로 열지 않는다. 허용하는 것은 임의의 웹사이트가 아니라
     * 사용자가 자기 크롬에 설치한 확장뿐이고, 운영에서는 고정 ID 하나로 좁힌다.
     */
    private org.springframework.web.cors.CorsConfiguration naverCorsConfiguration() {
        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
        java.util.List<String> patterns = new java.util.ArrayList<>(java.util.List.of(allowedOrigins.split(",")));
        patterns.addAll(java.util.List.of(allowedExtensionOrigins.split(",")));
        config.setAllowedOriginPatterns(patterns.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Idempotency-Key",
                "X-Extension-Token"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
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
