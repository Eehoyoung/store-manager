package com.storemanager.api.naver;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.naver.NaverDtos.PairResponse;
import com.storemanager.api.naver.NaverDtos.StoreRef;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 크롬 확장 페어링(1회용 코드 → 불투명 토큰)과 일괄승인 PIN.
 * Redis 저장 패턴은 {@code AuthService} 의 {@code rt:} 회전 토큰과 동일하다.
 *
 * <p>★ 네이버 ID/PW/세션/쿠키는 여기서도 다루지 않는다. 여기서 발급하는 토큰은
 * "이 사람이 우리 서비스에 로그인한 사장님 본인이다" 만 증명한다(같은 principal 형태로 JWT 와 수렴).
 */
@Service
public class ExtensionAuthService {

    private static final String PAIR_CODE_PREFIX = "extpair:";
    private static final String TOKEN_PREFIX = "ext:";
    private static final Duration PAIR_CODE_TTL = Duration.ofSeconds(300);
    private static final Duration TOKEN_TTL = Duration.ofDays(30);

    // I/O/0/1 제외 — 전화로 코드를 불러줄 때 혼동을 막기 위함(FranchiseService.CODE_ALPHABET 과 같은 이유).
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final Pattern PIN_PATTERN = Pattern.compile("\\d{4,8}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final AppUserRepository appUserRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;

    public ExtensionAuthService(StringRedisTemplate redisTemplate, AppUserRepository appUserRepository,
            StoreRepository storeRepository, PasswordEncoder passwordEncoder) {
        this.redisTemplate = redisTemplate;
        this.appUserRepository = appUserRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** POST /api/v1/naver/extension/pairing-code (JWT). 5분 뒤 만료되는 1회용 코드를 발급한다. */
    public String issuePairingCode(UUID userPublicId) {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        String value = code.toString();
        redisTemplate.opsForValue().set(PAIR_CODE_PREFIX + value, userPublicId.toString(), PAIR_CODE_TTL);
        return value;
    }

    /**
     * POST /api/v1/naver/extension/pair (무인증). 코드를 불투명 토큰으로 1회 교환한다(GETDEL).
     *
     * <p>★ 응답에 페어링한 사용자가 소유한 매장 목록을 함께 담는다. 확장은 storeId 를 몰라서
     * drafts·events·bulk-approve·status 등 이후 모든 호출을 못 하는 상태였다(storeId 필수 파라미터).
     * storeId 는 항상 {@code store.public_id}(UUID) 문자열이다 — 내부 BIGSERIAL 은 내보내지 않는다.
     */
    public PairResponse pair(String rawCode) {
        String normalized = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        String key = PAIR_CODE_PREFIX + normalized;
        String userPublicId = redisTemplate.opsForValue().get(key);
        if (userPublicId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("code", "코드가 올바르지 않거나 만료되었습니다."));
        }
        redisTemplate.delete(key); // 1회용
        String token = generateToken();
        redisTemplate.opsForValue().set(TOKEN_PREFIX + token, userPublicId, TOKEN_TTL);
        List<StoreRef> stores = ownedStores(UUID.fromString(userPublicId));
        return new PairResponse(token, TOKEN_TTL.toSeconds(), stores);
    }

    private List<StoreRef> ownedStores(UUID userPublicId) {
        AppUser owner = appUserRepository.findByPublicId(userPublicId).orElse(null);
        if (owner == null) {
            return List.of();
        }
        return storeRepository.findByOwnerIdAndDeletedAtIsNull(owner.getId()).stream()
                .map(store -> new StoreRef(store.getPublicId().toString(), store.getName()))
                .collect(Collectors.toList());
    }

    /** ExtensionTokenFilter 전용. 유효하면 슬라이딩 TTL 갱신 후 userPublicId 문자열을 반환, 아니면 null. */
    public String resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = TOKEN_PREFIX + token;
        String userPublicId = redisTemplate.opsForValue().get(key);
        if (userPublicId == null) {
            return null;
        }
        redisTemplate.expire(key, TOKEN_TTL);
        return userPublicId;
    }

    public void revoke(String token) {
        if (token != null) {
            redisTemplate.delete(TOKEN_PREFIX + token);
        }
    }

    /** POST /api/v1/naver/extension/pin (JWT). 공용 포스 PC 대리승인을 막는 유일한 장치다. */
    public void setPin(UUID userPublicId, String pin) {
        if (pin == null || !PIN_PATTERN.matcher(pin).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("pin", "PIN은 숫자 4~8자리여야 합니다."));
        }
        AppUser user = appUserRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        user.assignNaverBulkPinHash(passwordEncoder.encode(pin));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
