package com.storemanager.api.user;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.security.JwtTokenProvider;
import com.storemanager.api.store.StoreService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입/로그인/토큰갱신/로그아웃.
 * Refresh 토큰은 Redis 에 rt:{token} → userPublicId 로 저장하고, 갱신 시마다 폐기 후 재발급(회전)한다.
 */
@Service
public class AuthService {

    private static final String REFRESH_KEY_PREFIX = "rt:";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final StoreService storeService;

    public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider, StringRedisTemplate redisTemplate, StoreService storeService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
        this.storeService = storeService;
    }

    @Transactional
    public TokenPair signup(SignupRequest req) {
        appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.email())
                .ifPresent(u -> {
                    throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
                });

        AppUser user = AppUser.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(req.phone())
                .build();
        appUserRepository.save(user);
        storeService.createStore(user, req.storeName(), req.storeAddress());
        return issueTokens(user);
    }

    @Transactional
    public TokenPair login(LoginRequest req) {
        // 이메일 존재 여부를 구분해서 알려주지 않기 위해 두 실패 케이스 모두 동일한 UNAUTHORIZED 를 던진다.
        AppUser user = appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.email())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        user.recordLogin(Instant.now());
        return issueTokens(user);
    }

    @Transactional
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String key = REFRESH_KEY_PREFIX + refreshToken;
        String userPublicId = redisTemplate.opsForValue().get(key);
        if (userPublicId == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        redisTemplate.delete(key); // 회전: 기존 토큰 즉시 폐기
        AppUser user = appUserRepository.findByPublicId(UUID.fromString(userPublicId))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return issueTokens(user);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            redisTemplate.delete(REFRESH_KEY_PREFIX + refreshToken);
        }
    }

    @Transactional(readOnly = true)
    public AppUser getCurrentUser(UUID publicId) {
        return appUserRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @Transactional
    public AppUser updateProfile(UUID publicId, UpdateProfileRequest req) {
        AppUser user = getCurrentUser(publicId);
        user.updateProfile(req.name(), req.phone());
        return user;
    }

    @Transactional
    public void changePassword(UUID publicId, ChangePasswordRequest req) {
        AppUser user = getCurrentUser(publicId);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        user.changePassword(passwordEncoder.encode(req.newPassword()));
    }

    private TokenPair issueTokens(AppUser user) {
        String publicId = user.getPublicId().toString();
        String accessToken = jwtTokenProvider.createAccessToken(publicId);
        String refreshToken = jwtTokenProvider.createRefreshToken();
        redisTemplate.opsForValue().set(REFRESH_KEY_PREFIX + refreshToken, publicId,
                Duration.ofSeconds(jwtTokenProvider.getRefreshTtlSeconds()));
        return new TokenPair(accessToken, refreshToken, jwtTokenProvider.getAccessTtlSeconds(), user);
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn, AppUser user) {
    }
}
