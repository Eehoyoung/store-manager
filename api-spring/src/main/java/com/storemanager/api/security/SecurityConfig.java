package com.storemanager.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.common.ErrorResponse;
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
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(java.util.List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        // ★ /internal/** 은 제외한다. 워커 전용 경로이며 브라우저에서 호출될 일이 없다.
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        // /internal/** 는 JWT 가 아니라 X-Internal-Token 공유 시크릿으로 인증한다(컨트롤러에서 검증).
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(this::handleUnauthorized)
                        .accessDeniedHandler(this::handleForbidden))
                .addFilterBefore(new JwtAuthFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);
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
