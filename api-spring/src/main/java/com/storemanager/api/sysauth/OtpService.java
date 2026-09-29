package com.storemanager.api.sysauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 시스템 관리자·가맹본부 담당자 공용 이메일 OTP (docs/26a auth.otp).
 *
 * <p>6자리 SecureRandom 코드를 해시로만 저장한다({@code otp:{kind}:{HMAC-SHA256(email)}}).
 * 키에 이메일 원문 대신 HMAC 을 써서 Redis 를 들여다봐도 어떤 이메일이 요청했는지 알 수 없다.
 *
 * <p>★ 요청 응답은 이 서비스의 결과와 무관하게 항상 202 {@code {sent:true}} 다 — 계정 존재
 * 여부·요청 남용 여부를 응답으로 구분해서 알려주지 않는다. 호출부(AdminAuthService/HqAuthService)는
 * {@link #issue} 의 반환값(Optional)을 응답 분기에 쓰지 않고 메일 발송 여부에만 쓴다.
 */
@Component
public class OtpService {

    public enum Kind {
        ADMIN(Duration.ofSeconds(120)), HQ(Duration.ofSeconds(300));

        final Duration ttl;

        Kind(Duration ttl) {
            this.ttl = ttl;
        }
    }

    private static final Duration EMAIL_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration EMAIL_HOUR_WINDOW = Duration.ofHours(1);
    private static final int EMAIL_HOUR_LIMIT = 5;
    private static final Duration IP_HOUR_WINDOW = Duration.ofHours(1);
    private static final int IP_HOUR_LIMIT = 30;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditLogRepository auditLogRepository;
    private final SecretKey hmacKey;

    public OtpService(StringRedisTemplate redis, ObjectMapper objectMapper, AuditLogRepository auditLogRepository,
            @Value("${app.jwt.secret}") String jwtSecret) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.auditLogRepository = auditLogRepository;
        this.hmacKey = deriveKey(jwtSecret);
    }

    /**
     * OTP 를 만들어 저장하고 평문 코드를 반환한다(메일 발송용). 속도 제한에 걸리면 아무것도
     * 만들지 않고 빈 값을 반환한다 — 호출부는 이 경우에도 API 응답을 똑같이 202 로 낸다.
     */
    public Optional<String> issue(Kind kind, String email, String ip) {
        String normalized = normalize(email);
        if (rateLimited(kind, normalized, ip)) {
            audit("OTP_RATE_LIMITED", kind);
            return Optional.empty();
        }
        String code = generateCode();
        String key = otpKey(kind, normalized);
        redis.opsForValue().set(key, hash(code), kind.ttl);
        redis.delete(key + ":attempts"); // 새 코드는 시도 횟수를 새로 센다
        audit("OTP_REQUESTED", kind);
        return Optional.of(code);
    }

    /**
     * 검증 성공 시 즉시 삭제(재사용 불가). 오답 5회면 삭제(재요청 필요).
     *
     * <p>★ 시도 횟수는 비교 <b>전에</b> INCR 로 센다. 값을 읽고-고쳐-쓰는 방식이면 동시 요청이 모두
     * attempts=0 을 읽어 5회 제한을 넘겨 추측할 수 있다(6자리라 병렬 수천 건이면 의미 있는 확률이다).
     */
    public boolean verify(Kind kind, String email, String code) {
        String key = otpKey(kind, normalize(email));
        String attemptsKey = key + ":attempts";
        String codeHash = redis.opsForValue().get(key);
        if (codeHash == null || code == null) {
            audit("OTP_FAILED", kind);
            return false;
        }
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1) {
            redis.expire(attemptsKey, kind.ttl);
        }
        if (attempts == null || attempts > MAX_ATTEMPTS) {
            redis.delete(key);
            audit("OTP_FAILED", kind);
            return false;
        }
        // 성공은 키 삭제에 성공한 한 요청만 인정한다 — 같은 코드로 동시에 두 세션이 생기지 않게.
        if (constantTimeEquals(codeHash, hash(code)) && Boolean.TRUE.equals(redis.delete(key))) {
            redis.delete(attemptsKey);
            audit("OTP_VERIFIED", kind);
            return true;
        }
        audit("OTP_FAILED", kind);
        if (attempts >= MAX_ATTEMPTS) {
            redis.delete(key);
        }
        return false;
    }

    private boolean rateLimited(Kind kind, String email, String ip) {
        String cooldownKey = "otpcooldown:" + kind + ":" + hmac(email);
        Boolean firstInWindow = redis.opsForValue().setIfAbsent(cooldownKey, "1", EMAIL_COOLDOWN);
        if (Boolean.FALSE.equals(firstInWindow)) {
            return true;
        }
        String hourKey = "otphour:" + kind + ":" + hmac(email);
        Long emailCount = redis.opsForValue().increment(hourKey);
        if (emailCount != null && emailCount == 1) {
            redis.expire(hourKey, EMAIL_HOUR_WINDOW);
        }
        if (emailCount != null && emailCount > EMAIL_HOUR_LIMIT) {
            return true;
        }
        if (ip != null && !ip.isBlank()) {
            String ipKey = "otpip:" + kind + ":" + ip;
            Long ipCount = redis.opsForValue().increment(ipKey);
            if (ipCount != null && ipCount == 1) {
                redis.expire(ipKey, IP_HOUR_WINDOW);
            }
            if (ipCount != null && ipCount > IP_HOUR_LIMIT) {
                return true;
            }
        }
        return false;
    }

    private String otpKey(Kind kind, String email) {
        return "otp:" + kind + ":" + hmac(email);
    }

    private String hmac(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(email.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OTP 키 파생에 실패했습니다.", e);
        }
    }

    private static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((secret + ":otp-key-v1").getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static String hash(String code) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** ★ 이메일·OTP 원문은 절대 담지 않는다 — kind 만 남긴다(docs/26a auth.audit). */
    private void audit(String action, Kind kind) {
        try {
            auditLogRepository.save(AuditLog.builder().actorType(kind.name()).action(action)
                    .detail(objectMapper.writeValueAsString(Map.of("kind", kind.name()))).build());
        } catch (JsonProcessingException e) {
            // 감사 기록 실패로 인증 흐름을 막지 않는다.
        }
    }
}
