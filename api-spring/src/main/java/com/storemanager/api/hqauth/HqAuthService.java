package com.storemanager.api.hqauth;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.sysauth.OtpService;
import com.storemanager.api.sysauth.SessionService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 가맹본부 담당자 이메일 OTP 로그인 (docs/26a endpoints.hqAuth). OTP 5분 · 세션 8시간 · 자동연장 없음.
 *
 * <p>세션의 subject 는 {@code AppUser.publicId} 다 — 기존 {@code CurrentUser.publicId()} /
 * {@code HqAccessGuard} 를 그대로 재사용하기 위함이다(HQ 담당자도 app_user 행을 갖는다).
 */
@Service
public class HqAuthService {

    private static final Duration OTP_TTL = Duration.ofSeconds(300);
    static final long SESSION_TTL_SECONDS = 8 * 3600L;

    private final AppUserRepository appUserRepository;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final OtpService otpService;
    private final SessionService sessionService;
    private final MailService mailService;
    private final JwtTokenProvider jwtTokenProvider;

    public HqAuthService(AppUserRepository appUserRepository, FranchiseHqMemberRepository hqMemberRepository,
            OtpService otpService, SessionService sessionService, MailService mailService,
            JwtTokenProvider jwtTokenProvider) {
        this.appUserRepository = appUserRepository;
        this.hqMemberRepository = hqMemberRepository;
        this.otpService = otpService;
        this.sessionService = sessionService;
        this.mailService = mailService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** ★ 등록 여부·자격 여부와 무관하게 컨트롤러는 항상 202 를 반환한다. */
    public void request(String email, String ip) {
        String normalized = normalize(email);
        AppUser user = appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(normalized).orElse(null);
        if (user == null || hqMemberRepository.findActiveEligibleMemberships(user.getId()).isEmpty()) {
            return;
        }
        otpService.issue(OtpService.Kind.HQ, normalized, ip).ifPresent(code -> mailService.sendOtp(normalized, code, OTP_TTL));
    }

    public Result verify(String email, String code) {
        String normalized = normalize(email);
        AppUser user = appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(normalized).orElse(null);
        if (user == null || hqMemberRepository.findActiveEligibleMemberships(user.getId()).isEmpty()
                || !otpService.verify(OtpService.Kind.HQ, normalized, code)) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        }
        Instant now = Instant.now();
        for (FranchiseHqMember member : hqMemberRepository.findByUserId(user.getId())) {
            if (member.isActive()) {
                member.recordLogin(now);
            }
        }
        return issue(user);
    }

    public Result refresh(String sid) {
        String subjectPublicId = sessionService.subject(SessionService.Kind.HQ, sid)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        AppUser user;
        try {
            user = appUserRepository.findByPublicId(UUID.fromString(subjectPublicId))
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (hqMemberRepository.findActiveEligibleMemberships(user.getId()).isEmpty()) {
            sessionService.revoke(SessionService.Kind.HQ, sid);
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        long remaining = sessionService.remainingSeconds(SessionService.Kind.HQ, sid);
        if (remaining <= 0) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = jwtTokenProvider.createAccessToken(subjectPublicId, "HQ", sid, remaining);
        return new Result(accessToken, sid, remaining, user.getName(), user.getEmail());
    }

    public void logout(String sid) {
        sessionService.revoke(SessionService.Kind.HQ, sid);
    }

    private Result issue(AppUser user) {
        String subject = user.getPublicId().toString();
        String sid = sessionService.create(SessionService.Kind.HQ, subject, Duration.ofSeconds(SESSION_TTL_SECONDS));
        sessionService.trackHqSession(user.getId(), sid, Duration.ofSeconds(SESSION_TTL_SECONDS));
        String accessToken = jwtTokenProvider.createAccessToken(subject, "HQ", sid, SESSION_TTL_SECONDS);
        return new Result(accessToken, sid, SESSION_TTL_SECONDS, user.getName(), user.getEmail());
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record Result(String accessToken, String sid, long expiresInSeconds, String name, String email) {
    }
}
