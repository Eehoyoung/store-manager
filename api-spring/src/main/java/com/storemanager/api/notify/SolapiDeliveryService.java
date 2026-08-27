package com.storemanager.api.notify;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증된 SOLAPI 웹훅의 메시지 상태를 멱등 반영한다. */
@Service
class SolapiDeliveryService {

    private final NotificationLogRepository logs;

    SolapiDeliveryService(NotificationLogRepository logs) {
        this.logs = logs;
    }

    @Transactional
    boolean apply(String messageId, String statusCode, String statusMessage) {
        NotificationLog log = logs.findByProviderMessageId(messageId).orElse(null);
        if (log == null) {
            return false;
        }
        if ("4000".equals(statusCode) && !"DELIVERED".equals(log.getStatus())) {
            log.markDelivered(Instant.now());
        } else if (isFailure(statusCode) && !"DELIVERED".equals(log.getStatus())) {
            log.markFailed(statusCode, safeMessage(statusMessage));
        }
        // 2000(접수·대기), 3000(발송 중), 미확인 코드는 현재 상태를 유지한다.
        return true;
    }

    private static boolean isFailure(String code) {
        return code != null && ((code.startsWith("1"))
                || (code.startsWith("2") && !"2000".equals(code))
                || (code.startsWith("3") && !"3000".equals(code))
                || code.startsWith("5") || "99999".equals(code));
    }

    private static String safeMessage(String ignored) {
        // 공급자 문구에 전화번호나 원문이 포함될 수 있으므로 DB에는 고정 문구만 남긴다.
        return "SOLAPI 최종 전달 실패";
    }
}
