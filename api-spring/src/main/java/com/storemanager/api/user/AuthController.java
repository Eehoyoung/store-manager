package com.storemanager.api.user;

import com.storemanager.api.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/13 §2. Refresh 토큰은 응답 바디에 담지 않고 HttpOnly 쿠키로만 전달한다(공통규약 "HttpOnly 쿠키").
 * 카카오 소셜 로그인, 휴대폰 인증은 Sprint 1 범위 밖이라 만들지 않는다.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthService authService, JwtTokenProvider jwtTokenProvider) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest req, HttpServletResponse res) {
        return respond(authService.signup(req), res);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        return respond(authService.login(req), res);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse res) {
        return respond(authService.refresh(refreshToken), res);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse res) {
        authService.logout(refreshToken);
        clearCookie(res);
    }

    private AuthResponse respond(AuthService.TokenPair pair, HttpServletResponse res) {
        setCookie(res, pair.refreshToken());
        AppUser u = pair.user();
        UserSummary summary = new UserSummary(u.getPublicId().toString(), u.getName(), u.getEmail());
        return new AuthResponse(pair.accessToken(), pair.expiresIn(), summary);
    }

    private void setCookie(HttpServletResponse res, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true).secure(true).sameSite("Strict").path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(jwtTokenProvider.getRefreshTtlSeconds()))
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse res) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
