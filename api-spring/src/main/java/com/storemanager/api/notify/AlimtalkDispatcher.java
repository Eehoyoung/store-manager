package com.storemanager.api.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** notification_log의 신규 고위험 알림만 SOLAPI에 접수한다. */
@Service
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class AlimtalkDispatcher {

    private static final String HIGH_RISK = "HIGH_RISK_REVIEW";
    private final NotificationLogRepository logs;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final SolapiSender sender;
    private final ObjectMapper objectMapper;
    private final AlimtalkProperties properties;

    AlimtalkDispatcher(NotificationLogRepository logs, AppUserRepository users, StoreRepository stores,
            SolapiSender sender, ObjectMapper objectMapper, AlimtalkProperties properties) {
        this.logs = logs;
        this.users = users;
        this.stores = stores;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 건별 실패를 흡수해 다른 매장의 발송을 계속한다. */
    @Transactional
    int dispatchDue() {
        // ponytail: 초기 100매장은 한 배치의 DB 잠금으로 중복 발송을 막는다.
        // 다중 인스턴스 대기 시간이 문제가 되면 SKIP LOCKED 기반 claim 쿼리로 바꾼다.
        int accepted = 0;
        for (NotificationLog log : logs.findTop20ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(
                "ALIMTALK", "QUEUED", Instant.now())) {
            log.markSending();
            if (!HIGH_RISK.equals(log.getTemplate())) {
                log.markSkipped("TEMPLATE_NOT_ENABLED");
                continue;
            }
            var user = log.getUserId() == null ? null : users.findById(log.getUserId()).orElse(null);
            if (user == null || user.getDeletedAt() != null || !"ACTIVE".equals(user.getStatus())) {
                log.markSkipped("RECIPIENT_INACTIVE");
                continue;
            }
            if (user.getPhoneVerifiedAt() == null) {
                log.markSkipped("PHONE_NOT_VERIFIED");
                continue;
            }
            String phone = SolapiSender.digits(user.getPhone());
            if (!phone.matches("^01[016789][0-9]{7,8}$")) {
                log.markSkipped("INVALID_PHONE");
                continue;
            }
            try {
                SolapiSender.SendResult result = sender.sendHighRisk(phone, variables(log));
                log.markAccepted(result.messageId());
                accepted++;
            } catch (Exception e) {
                // 접수 결과가 모호한 예외를 자동 재시도하면 같은 알림이 두 번 갈 수 있어 종결한다.
                log.markFailed("SOLAPI_ACCEPT_FAILED", "SOLAPI 발송 접수 결과를 확인하지 못했습니다.");
            }
        }
        return accepted;
    }

    private Map<String, String> variables(NotificationLog log) {
        Map<String, String> vars = new LinkedHashMap<>();
        try {
            vars.putAll(objectMapper.readValue(log.getPayload(), new TypeReference<>() {}));
        } catch (Exception ignored) {
            // 과거 빈/깨진 payload는 민감정보를 추정하지 않고 안전한 변수만 새로 채운다.
        }
        if (log.getStoreId() != null) {
            stores.findById(log.getStoreId()).ifPresent(store -> {
                vars.putIfAbsent("storeName", store.getName());
                vars.putIfAbsent("reviewUrl", properties.getLinkBaseUrl() + "/stores/" + store.getPublicId()
                        + "/reviews?riskLevel=3");
            });
        }
        return vars;
    }
}
