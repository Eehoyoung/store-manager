package com.storemanager.api.notify;

import org.springframework.stereotype.Component;

/** 알림 이벤트를 기록하고, 활성화된 고위험 알림톡만 발송 대기열에 넣는다. */
@Component
public class LoggingNotifier implements Notifier {

    private final NotificationLogRepository notificationLogRepository;
    private final AlimtalkProperties properties;

    public LoggingNotifier(NotificationLogRepository notificationLogRepository, AlimtalkProperties properties) {
        this.notificationLogRepository = notificationLogRepository;
        this.properties = properties;
    }

    @Override
    public void send(Long userId, Long storeId, String channel, String template, String refType, Long refId) {
        send(userId, storeId, channel, template, refType, refId, java.util.Map.of());
    }

    /** 비활성화 상태와 미승인 템플릿은 RECORDED로 남겨 실제 발송과 구분한다. */
    @Override
    public void send(Long userId, Long storeId, String channel, String template, String refType, Long refId,
            java.util.Map<String, String> vars) {
        String payload = "{}";
        if (vars != null && !vars.isEmpty()) {
            try {
                payload = MAPPER.writeValueAsString(vars);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // 변수 직렬화 실패로 알림 자체를 막지 않는다 — 알림이 가는 것이 더 중요하다.
                payload = "{}";
            }
        }
        boolean queued = properties.isEnabled() && "ALIMTALK".equals(channel)
                && "HIGH_RISK_REVIEW".equals(template);
        if (queued) {
            notificationLogRepository.enqueueHighRiskIfAbsent(userId, storeId, refType, refId, payload);
            return;
        }
        notificationLogRepository.save(NotificationLog.builder()
                .userId(userId)
                .storeId(storeId)
                .channel(channel)
                .template(template)
                .status("RECORDED")
                .refType(refType)
                .refId(refId)
                .payload(payload)
                .build());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
