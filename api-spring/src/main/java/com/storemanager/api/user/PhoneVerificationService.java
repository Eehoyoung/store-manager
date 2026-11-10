package com.storemanager.api.user;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.notify.SolapiSmsSender;
import com.storemanager.api.sysauth.OtpService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 휴대폰 번호 확인(app_user.phone_verified_at). SMS 6자리 인증번호, 3분 유효.
 *
 * <p>★ 요청 응답은 발송·상한 초과·발송 실패와 무관하게 항상 같다(남용 여부를 응답으로 알려주지 않는다).
 * <p>★ 전화번호·인증번호 원문은 로그에 남기지 않는다(번호는 끝 4자리까지만).
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final int DAILY_LIMIT = 10; // 회원당 하루 상한(대표 승인)

    private final AppUserRepository appUserRepository;
    private final OtpService otpService;
    private final StringRedisTemplate redis;
    private final ObjectProvider<SolapiSmsSender> senderProvider;

    public PhoneVerificationService(AppUserRepository appUserRepository, OtpService otpService,
            StringRedisTemplate redis, ObjectProvider<SolapiSmsSender> senderProvider) {
        this.appUserRepository = appUserRepository;
        this.otpService = otpService;
        this.redis = redis;
        this.senderProvider = senderProvider;
    }

    public boolean isEnabled() {
        return senderProvider.getIfAvailable() != null;
    }

    public void request(UUID publicId, String ip) {
        SolapiSmsSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AppUser user = find(publicId);
        String digits = digits(user.getPhone());
        if (digits.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        String dayKey = "phoneverify:day:" + publicId;
        Long count = redis.opsForValue().increment(dayKey);
        if (count != null && count == 1) {
            redis.expire(dayKey, Duration.ofHours(24));
        }
        if (count != null && count > DAILY_LIMIT) {
            return; // 조용히 생략 - 응답은 똑같이 202
        }
        otpService.issue(OtpService.Kind.PHONE, identifier(publicId, digits), ip).ifPresent(code -> {
            try {
                sender.send(digits, "[소담리뷰] 인증번호 " + code + " (3분 안에 입력해 주세요)");
            } catch (Exception e) {
                // 번호·코드 원문 없이 끝 4자리와 예외 종류만 남긴다.
                log.warn("휴대폰 인증번호 발송 실패 phone=***{} cause={}", tail(digits), e.getClass().getSimpleName());
            }
        });
    }

    @Transactional
    public AppUser confirm(UUID publicId, String code) {
        AppUser user = find(publicId);
        String digits = digits(user.getPhone());
        if (digits.isEmpty() || !otpService.verify(OtpService.Kind.PHONE, identifier(publicId, digits), code)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        user.markPhoneVerified(Instant.now());
        return user;
    }

    private AppUser find(UUID publicId) {
        return appUserRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private static String identifier(UUID publicId, String digits) {
        return publicId + ":" + digits;
    }

    private static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    private static String tail(String digits) {
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }
}
