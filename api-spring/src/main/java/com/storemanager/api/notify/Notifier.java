package com.storemanager.api.notify;

/**
 * 알림 발송 인터페이스(S11). 실제 알림톡 벤더가 미정이라 구현체는 notification_log 적재만 한다.
 * TODO: 알림톡 벤더 확정 후 실제 발송(카카오 알림톡 등) 구현체를 추가한다. 지금은 로깅만 한다.
 */
public interface Notifier {

    void send(Long userId, Long storeId, String channel, String template, String refType, Long refId);
}
