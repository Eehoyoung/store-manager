package com.storemanager.api.agreement;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgreementService {
    public static final String CURRENT_VERSION = "2026-08-28";
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

    private static java.net.InetAddress toAddress(String value) {
        if (value == null) return null;
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
