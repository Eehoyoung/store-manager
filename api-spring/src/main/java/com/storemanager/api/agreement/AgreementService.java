package com.storemanager.api.agreement;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgreementService {
    /**
     * 동의 문서 버전. {@code resources/agreements/<버전>/} 디렉터리 이름과 같아야 한다.
     *
     * <p><b>★ 올리면 기존 동의가 현행이 아니게 된다</b>({@link #requireCurrentVersion}).
     * 문구를 고쳤는데 버전을 안 올리면 "무엇에 동의했는지" 를 답할 수 없게 되므로,
     * 문서를 고칠 때는 새 디렉터리를 만들고 여기를 함께 올린다. 옛 디렉터리는 지우지 않는다 —
     * 과거 동의가 가리키는 문서다.
     */
    public static final String CURRENT_VERSION = "2026-09-20";
    public static final String TERMS = "TERMS_OF_SERVICE";
    public static final String PRIVACY = "PRIVACY_POLICY";
    public static final String HQ = "HQ_DATA_SHARING";
    public static final String CREDENTIAL = "PLATFORM_CREDENTIAL";

    private final UserAgreementRepository repository;

    public AgreementService(UserAgreementRepository repository) {
        this.repository = repository;
    }

    public void requireCurrentVersion(String version) {
        if (!CURRENT_VERSION.equals(version)) {
            throw new ApiException(ErrorCode.CONSENT_VERSION_MISMATCH,
                    Map.of("currentVersion", CURRENT_VERSION));
        }
    }

    public void record(Long userId, Long storeId, String code, boolean agreed, String ip, String userAgent) {
        repository.save(UserAgreement.builder()
                .userId(userId).storeId(storeId).agreementCode(code).docVersion(CURRENT_VERSION)
                .agreed(agreed).agreedAt(Instant.now()).ip(toAddress(ip)).userAgent(trim(userAgent, 300)).build());
    }

    public List<AgreementHistoryRow> history(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(a -> new AgreementHistoryRow(a.getAgreementCode(), a.isAgreed(), a.getAgreedAt(),
                        a.getDocVersion(), "/legal/" + slug(a.getAgreementCode())))
                .toList();
    }

    private static String trim(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    /**
     * IP 리터럴만 받는다.
     *
     * <p>★ {@code InetAddress.getByName} 은 리터럴이 아니면 <b>DNS 를 조회한다.</b> 이 값은
     * {@code X-Forwarded-For} 에서 오고 그 헤더는 클라이언트가 넣는다 — 거르지 않으면 미인증
     * 회원가입 경로가 임의 호스트명을 조회하게 되고, 그 블로킹 호출이 가입 트랜잭션 안에서 돈다.
     * 리터럴로 확인된 값에는 getByName 이 이름 해석을 하지 않는다.
     */
    static java.net.InetAddress toAddress(String value) {
        if (value == null) {
            return null;
        }
        boolean ipv4 = value.matches("[0-9.]+");
        boolean ipv6 = value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return java.net.InetAddress.getByName(value);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static String slug(String code) {
        return switch (code) {
            case TERMS -> "terms";
            case PRIVACY -> "privacy";
            case HQ -> "hq-data-sharing";
            case CREDENTIAL -> "platform-credential";
            default -> "terms";
        };
    }

    public record AgreementHistoryRow(String code, boolean agreed, Instant agreedAt, String docVersion,
            String documentUrl) {}
}
