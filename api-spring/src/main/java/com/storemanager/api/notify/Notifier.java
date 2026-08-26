package com.storemanager.api.notify;

/**
 * 알림 발송 인터페이스(S11). 실제 알림톡 벤더가 미정이라 구현체는 notification_log 적재만 한다.
 * TODO: 알림톡 벤더 확정 후 실제 발송(카카오 알림톡 등) 구현체를 추가한다. 지금은 로깅만 한다.
 */
public interface Notifier {

    void send(Long userId, Long storeId, String channel, String template, String refType, Long refId);

    /**
     * 템플릿 변수를 함께 싣는다. 카카오 알림톡은 승인된 템플릿에 변수만 치환해 보내므로,
     * 무엇을 채워 보냈는지 남기지 않으면 나중에 "왜 이 숫자가 갔나" 에 답할 수 없다.
     *
     * <p>기본 구현은 변수를 버리고 기존 경로로 위임한다 — 구현체가 아직 변수를 다루지
     * 못해도 알림 자체는 끊기지 않아야 한다.
     */
    default void send(Long userId, Long storeId, String channel, String template, String refType, Long refId,
            java.util.Map<String, String> vars) {
        send(userId, storeId, channel, template, refType, refId);
    }
}
