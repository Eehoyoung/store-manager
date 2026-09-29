package com.storemanager.api.hqauth;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.hqauth.HqAuthDtos.EmailRequest;
import com.storemanager.api.hqauth.HqAuthDtos.HqAuthResponse;
import com.storemanager.api.hqauth.HqAuthDtos.HqUserSummary;
import com.storemanager.api.hqauth.HqAuthDtos.SentResponse;
import com.storemanager.api.hqauth.HqAuthDtos.VerifyRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 가맹본부 담당자 이메일 OTP 로그인 (docs/26a endpoints.hqAuth). */
@RestController
@RequestMapping("/api/v1/hq-auth")
public class HqAuthController {

    private static final String SESSION_COOKIE = "hqSession";
    private static final String SESSION_COOKIE_PATH = "/api/v1/hq-auth";

    private final HqAuthService service;

    public HqAuthController(HqAuthService service) {
        this.service = service;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SentResponse request(@Valid @RequestBody EmailRequest req, HttpServletRequest request) {
        service.request(req.email(), clientIp(request));
        return new SentResponse(true);
    }

    @PostMapping("/verify")
    public HqAuthResponse verify(@Valid @RequestBody VerifyRequest req, HttpServletResponse res) {
        return respond(service.verify(req.email(), req.code()), res);
    }

    @PostMapping("/refresh")
    public HqAuthResponse refresh(@CookieValue(value = SESSION_COOKIE, required = false) String sid,
            HttpServletResponse res) {
        if (sid == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return respond(service.refresh(sid), res);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(value = SESSION_COOKIE, required = false) String sid, HttpServletResponse res) {
        service.logout(sid);
        clearCookie(res);
    }

    private HqAuthResponse respond(HqAuthService.Result result, HttpServletResponse res) {
        setCookie(res, result.sid(), result.expiresInSeconds());
        String expiresAt = Instant.now().plusSeconds(result.expiresInSeconds()).toString();
        return new HqAuthResponse(result.accessToken(), expiresAt, "HQ",
                new HqUserSummary(result.name(), result.email()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        try {
            java.net.InetAddress.getByName(value);
            return value;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private void setCookie(HttpServletResponse res, String sid, long ttlSeconds) {
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, sid)
                .httpOnly(true).secure(true).sameSite("Strict").path(SESSION_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(ttlSeconds)).build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse res) {
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path(SESSION_COOKIE_PATH).maxAge(0).build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
