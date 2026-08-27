package com.storemanager.api.notify;

import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 알림톡 claim과 결과 기록의 짧은 트랜잭션 경계. 외부 HTTP 호출은 이 클래스 밖에서 한다. */
@Service
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class AlimtalkDispatchTransactions {

    private static final String HIGH_RISK = "HIGH_RISK_REVIEW";
    private final NotificationLogRepository logs;
    private final AppUserRepository users;

    AlimtalkDispatchTransactions(NotificationLogRepository logs, AppUserRepository users) {
        this.logs = logs;
        this.users = users;
    }

    @Transactional
    Optional<Claim> claimNext() {
        var found = logs.findFirstByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(
                "ALIMTALK", "QUEUED", Instant.now());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NotificationLog log = found.orElseThrow();
        log.markSending();
        if (!HIGH_RISK.equals(log.getTemplate())) {
            log.markSkipped("TEMPLATE_NOT_ENABLED");
            return Optional.of(Claim.skipped(log.getId()));
        }
        var user = log.getUserId() == null ? null : users.findById(log.getUserId()).orElse(null);
        if (user == null || user.getDeletedAt() != null || !"ACTIVE".equals(user.getStatus())) {
            log.markSkipped("RECIPIENT_INACTIVE");
            return Optional.of(Claim.skipped(log.getId()));
        }
        if (user.getPhoneVerifiedAt() == null) {
            log.markSkipped("PHONE_NOT_VERIFIED");
            return Optional.of(Claim.skipped(log.getId()));
        }
        String phone = SolapiSender.digits(user.getPhone());
        if (!phone.matches("^01[016789][0-9]{7,8}$")) {
            log.markSkipped("INVALID_PHONE");
            return Optional.of(Claim.skipped(log.getId()));
        }
        return Optional.of(new Claim(log.getId(), log.getStoreId(), log.getPayload(), phone));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordAccepted(Long logId, String messageId) {
        logs.findById(logId).orElseThrow().markAccepted(messageId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailed(Long logId) {
        logs.findById(logId).orElseThrow()
                .markFailed("SOLAPI_ACCEPT_FAILED", "SOLAPI 발송 접수 결과를 확인하지 못했습니다.");
    }

    record Claim(Long logId, Long storeId, String payload, String phone) {
        static Claim skipped(Long logId) {
            return new Claim(logId, null, null, null);
        }

        boolean sendable() {
            return phone != null;
        }
    }
}
