package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.notify.SolapiSmsSender;
import com.storemanager.api.sysauth.OtpService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class PhoneVerificationServiceTest {

    private AppUserRepository repo;
    private OtpService otp;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ObjectProvider<SolapiSmsSender> provider;
    private SolapiSmsSender sender;
    private AppUser user;
    private PhoneVerificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        otp = mock(OtpService.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        provider = mock(ObjectProvider.class);
        sender = mock(SolapiSmsSender.class);
        user = AppUser.builder().email("a@b.c").name("홍길동").phone("010-1234-5678").build();
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(repo.findByPublicIdAndDeletedAtIsNull(user.getPublicId())).thenReturn(Optional.of(user));
        when(otp.issue(eq(OtpService.Kind.PHONE), anyString(), any())).thenReturn(Optional.of("123456"));
        service = new PhoneVerificationService(repo, otp, redis, provider);
    }

    @Test
    void 플래그가_꺼져_있으면_예외() {
        when(provider.getIfAvailable()).thenReturn(null);
        ApiException e = assertThrows(ApiException.class, () -> service.request(user.getPublicId(), "1.2.3.4"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(otp, never()).issue(any(), anyString(), any()); // 꺼져 있으면 인증번호도 만들지 않는다
    }

    @Test
    void 정상이면_숫자만_번호로_문안을_발송한다() throws Exception {
        service.request(user.getPublicId(), "1.2.3.4");
        verify(sender).send("01012345678", "[소담리뷰] 인증번호 123456 (3분 안에 입력해 주세요)");
    }

    @Test
    void 하루_10번째까지_발송하고_11번째는_발송하지_않는다() throws Exception {
        when(ops.increment(anyString())).thenReturn(10L);
        service.request(user.getPublicId(), "1.2.3.4");
        verify(sender).send(anyString(), anyString());

        when(ops.increment(anyString())).thenReturn(11L);
        service.request(user.getPublicId(), "1.2.3.4");
        verify(sender).send(anyString(), anyString()); // 여전히 1번 — 11번째는 생략
        verify(otp).issue(eq(OtpService.Kind.PHONE), anyString(), any()); // 코드도 만들지 않는다
    }

    @Test
    void 코드가_발급되지_않으면_발송하지_않는다() throws Exception {
        when(otp.issue(eq(OtpService.Kind.PHONE), anyString(), any())).thenReturn(Optional.empty());
        service.request(user.getPublicId(), "1.2.3.4");
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void 발송_예외는_삼킨다() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("x")).when(sender).send(anyString(), anyString());
        service.request(user.getPublicId(), "1.2.3.4");
    }

    @Test
    void 확인_성공이면_인증시각을_채운다() {
        when(otp.verify(eq(OtpService.Kind.PHONE), anyString(), eq("123456"))).thenReturn(true);
        assertThat(service.confirm(user.getPublicId(), "123456").getPhoneVerifiedAt()).isNotNull();
    }

    @Test
    void 확인_실패면_400() {
        when(otp.verify(eq(OtpService.Kind.PHONE), anyString(), anyString())).thenReturn(false);
        ApiException e = assertThrows(ApiException.class, () -> service.confirm(user.getPublicId(), "000000"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(user.getPhoneVerifiedAt()).isNull();
    }
}
