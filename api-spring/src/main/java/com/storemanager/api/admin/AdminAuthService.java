package com.storemanager.api.admin;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.sysauth.OtpService;
import com.storemanager.api.sysauth.SessionService;
import com.storemanager.api.security.JwtTokenProvider;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 시스템 관리자 이메일 OTP 로그인 (docs/26a endpoints.adminAuth). OTP 2분 · 세션 10분 · 자동연장 없음.
 */
@Service
public class AdminAuthService {

    private static final Duration OTP_TTL = Duration.ofSeconds(120);
    static final long SESSION_TTL_SECONDS = 600;

    private final AdminAccessGuard guard;
    private final OtpService otpService;
    private final SessionService sessionService;
    private final MailService mailService;
    private final JwtTokenProvider jwtTokenProvider;

    public AdminAuthService(AdminAccessGuard guard, OtpService otpService, SessionService sessionService,
            MailService mailService, JwtTokenProvider jwtTokenProvider) {
        this.guard = guard;
        this.otpService = otpService;
        this.sessionService = sessionService;
        this.mailService = mailService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** ★ 등록 여부와 무관하게 컨트롤러는 항상 202 를 반환한다 — 여기서 예외를 던지지 않는다. */
    public void request(String email, String ip) {
        String normalized = normalize(email);
        if (!guard.isAdminEmail(normalized)) {
            return;
        }
        otpService.issue(OtpService.Kind.ADMIN, normalized, ip)
                .ifPresent(code -> mailService.sendOtp(normalized, code, OTP_TTL));
    }

    public TokenResult verify(String email, String code) {
        String normalized = normalize(email);
        if (!guard.isAdminEmail(normalized) || !otpService.verify(OtpService.Kind.ADMIN, normalized, code)) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        }
        return issue(normalized);
    }

    public TokenResult refresh(String sid) {
        String email = sessionService.subject(SessionService.Kind.ADMIN, sid)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!guard.isAdminEmail(email)) {
            sessionService.revoke(SessionService.Kind.ADMIN, sid);
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        long remaining = sessionService.remainingSeconds(SessionService.Kind.ADMIN, sid);
        if (remaining <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = jwtTokenProvider.createAccessToken(email, "ADMIN", sid, remaining);
        return new TokenResult(accessToken, sid, remaining);
    }

    public void logout(String sid) {
        sessionService.revoke(SessionService.Kind.ADMIN, sid);
    }

    private TokenResult issue(String email) {
        String sid = sessionService.create(SessionService.Kind.ADMIN, email, Duration.ofSeconds(SESSION_TTL_SECONDS));
        String accessToken = jwtTokenProvider.createAccessToken(email, "ADMIN", sid, SESSION_TTL_SECONDS);
        return new TokenResult(accessToken, sid, SESSION_TTL_SECONDS);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record TokenResult(String accessToken, String sid, long expiresInSeconds) {
    }
}
