package com.storemanager.api.notify;

import org.springframework.stereotype.Component;

/**
 * 알림톡 벤더 미정 상태의 임시 구현(S11). notification_log 적재만 하고 실제 발송은 하지 않는다.
 * TODO: 벤더 확정 후 실제 발송 로직으로 교체하거나, 발송 성공/실패에 따라 status 를 분기한다.
 * 지금은 "발송 대상으로 기록됨"이라는 의미로 SENT 를 쓴다 — 실제 채널 전달을 보장하지 않는다.
 */
@Component
public class LoggingNotifier implements Notifier {

    private final NotificationLogRepository notificationLogRepository;

    public LoggingNotifier(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    @Override
    public void send(Long userId, Long storeId, String channel, String template, String refType, Long refId) {
        send(userId, storeId, channel, template, refType, refId, java.util.Map.of());
    }

    /**
     * ★ 아직 실제 발송은 하지 않는다(T-18). 벤더는 솔라피로 확정됐고 발신프로필·템플릿 승인이
     * 남았다. 지금은 <b>무엇을 언제 보내려 했는지</b>를 남기는 것이 전부다.
     * 어댑터를 붙일 때 이 클래스를 갈아끼우면 되고, 호출부는 손대지 않는다.
     */
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
        notificationLogRepository.save(NotificationLog.builder()
                .userId(userId)
                .storeId(storeId)
                .channel(channel)
                .template(template)
                .status("SENT")
                .refType(refType)
                .refId(refId)
                .payload(payload)
                .build());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
