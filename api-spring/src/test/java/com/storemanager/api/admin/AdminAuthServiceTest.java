package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.sysauth.OtpService;
import com.storemanager.api.sysauth.SessionService;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminAuthServiceTest {

    private AdminAccessGuard guard;
    private OtpService otpService;
    private SessionService sessionService;
    private MailService mailService;
    private JwtTokenProvider jwtTokenProvider;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        guard = new AdminAccessGuard("boss@sodam.test");
        otpService = mock(OtpService.class);
        sessionService = mock(SessionService.class);
        mailService = mock(MailService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        service = new AdminAuthService(guard, otpService, sessionService, mailService, jwtTokenProvider);
    }

    /** ★ 등록되지 않은 이메일은 OTP 를 만들지도, 메일을 보내지도 않는다 — 그래도 컨트롤러 응답은 동일하다. */
    @Test
    void 허용목록에_없는_이메일은_OTP를_만들지_않는다() {
        service.request("stranger@example.com", "1.2.3.4");

        verify(otpService, never()).issue(any(), anyString(), anyString());
        verify(mailService, never()).sendOtp(anyString(), anyString(), any());
    }

    @Test
    void 허용된_이메일은_OTP를_만들고_메일로_보낸다() {
        when(otpService.issue(OtpService.Kind.ADMIN, "boss@sodam.test", "1.2.3.4"))
                .thenReturn(Optional.of("123456"));

        service.request("boss@sodam.test", "1.2.3.4");

        verify(mailService).sendOtp("boss@sodam.test", "123456", Duration.ofSeconds(120));
    }

    @Test
    void 검증_실패는_OTP_INVALID_한종류다() {
        when(otpService.verify(OtpService.Kind.ADMIN, "boss@sodam.test", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.verify("boss@sodam.test", "000000"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.OTP_INVALID);
    }

    @Test
    void 허용목록에_없으면_코드가_맞아도_OTP_INVALID다() {
        assertThatThrownBy(() -> service.verify("stranger@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.OTP_INVALID);
    }

    @Test
    void 검증_성공하면_세션과_토큰을_발급한다() {
        when(otpService.verify(OtpService.Kind.ADMIN, "boss@sodam.test", "123456")).thenReturn(true);
        when(sessionService.create(SessionService.Kind.ADMIN, "boss@sodam.test", Duration.ofSeconds(600)))
                .thenReturn("sid-1");
        when(jwtTokenProvider.createAccessToken("boss@sodam.test", "ADMIN", "sid-1", 600))
                .thenReturn("jwt-token");

        AdminAuthService.TokenResult result = service.verify("boss@sodam.test", "123456");

        assertThat(result.accessToken()).isEqualTo("jwt-token");
        assertThat(result.sid()).isEqualTo("sid-1");
    }

    @Test
    void 세션이_없으면_refresh는_거절된다() {
        when(sessionService.subject(SessionService.Kind.ADMIN, "sid-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("sid-x"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /** ★ 세션은 있지만 그 사이 관리자 목록에서 빠졌으면(운영 변경) 즉시 거절하고 세션도 지운다. */
    @Test
    void 세션은_있지만_더이상_관리자가_아니면_거절하고_세션을_지운다() {
        AdminAccessGuard emptyGuard = new AdminAccessGuard("");
        AdminAuthService s = new AdminAuthService(emptyGuard, otpService, sessionService, mailService,
                jwtTokenProvider);
        when(sessionService.subject(SessionService.Kind.ADMIN, "sid-1")).thenReturn(Optional.of("boss@sodam.test"));

        assertThatThrownBy(() -> s.refresh("sid-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(sessionService).revoke(SessionService.Kind.ADMIN, "sid-1");
    }
}
