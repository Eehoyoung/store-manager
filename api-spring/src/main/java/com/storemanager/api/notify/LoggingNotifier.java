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
        notificationLogRepository.save(NotificationLog.builder()
                .userId(userId)
                .storeId(storeId)
                .channel(channel)
                .template(template)
                .status("SENT")
                .refType(refType)
                .refId(refId)
                .build());
    }
}
