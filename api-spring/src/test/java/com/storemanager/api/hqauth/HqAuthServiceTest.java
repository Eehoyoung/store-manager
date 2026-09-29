package com.storemanager.api.hqauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/26a endpoints.hqAuth — 자격(hqEligible) 판정과 OTP 흐름. */
class HqAuthServiceTest {

    private AppUserRepository appUserRepository;
    private FranchiseHqMemberRepository hqMemberRepository;
    private OtpService otpService;
    private SessionService sessionService;
    private MailService mailService;
    private JwtTokenProvider jwtTokenProvider;
    private HqAuthService service;

    private final UUID publicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        hqMemberRepository = mock(FranchiseHqMemberRepository.class);
        otpService = mock(OtpService.class);
        sessionService = mock(SessionService.class);
        mailService = mock(MailService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        service = new HqAuthService(appUserRepository, hqMemberRepository, otpService, sessionService, mailService,
                jwtTokenProvider);
    }

    @Test
    void 활성_멤버십이_없는_사용자는_OTP를_만들지_않는다() {
        AppUser user = AppUser.builder().id(1L).publicId(publicId).email("owner@sodam.test").name("사장").build();
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("owner@sodam.test"))
                .thenReturn(Optional.of(user));
        when(hqMemberRepository.findActiveEligibleMemberships(1L)).thenReturn(List.of());

        service.request("owner@sodam.test", "1.2.3.4");

        verify(otpService, never()).issue(any(), anyString(), anyString());
    }

    @Test
    void 존재하지_않는_이메일도_같은_응답이_되도록_예외를_던지지_않는다() {
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("ghost@sodam.test"))
                .thenReturn(Optional.empty());

        service.request("ghost@sodam.test", "1.2.3.4");

        verify(otpService, never()).issue(any(), anyString(), anyString());
    }

    @Test
    void 활성_멤버는_OTP를_받고_로그인하면_모든_멤버십의_로그인시각이_갱신된다() {
        AppUser user = AppUser.builder().id(1L).publicId(publicId).email("hq@sodam.test").name("담당자").build();
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("hq@sodam.test"))
                .thenReturn(Optional.of(user));
        FranchiseHqMember membership = FranchiseHqMember.builder().id(9L).userId(1L).brandName("브랜드A").build();
        when(hqMemberRepository.findActiveEligibleMemberships(1L)).thenReturn(List.of(membership));
        when(hqMemberRepository.findByUserId(1L)).thenReturn(List.of(membership));
        when(otpService.verify(OtpService.Kind.HQ, "hq@sodam.test", "123456")).thenReturn(true);
        when(sessionService.create(eq(SessionService.Kind.HQ), anyString(), any())).thenReturn("sid-hq");
        when(jwtTokenProvider.createAccessToken(anyString(), eq("HQ"), eq("sid-hq"), anyLong()))
                .thenReturn("jwt-hq");

        HqAuthService.Result result = service.verify("hq@sodam.test", "123456");

        assertThat(result.accessToken()).isEqualTo("jwt-hq");
        assertThat(result.name()).isEqualTo("담당자");
        assertThat(membership.getLastLoginAt()).isNotNull();
        verify(sessionService).trackHqSession(eq(1L), eq("sid-hq"), any());
    }

    @Test
    void 자격없는_사용자의_검증은_OTP_INVALID다() {
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("nobody@sodam.test"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("nobody@sodam.test", "123456"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.OTP_INVALID);
    }
}
