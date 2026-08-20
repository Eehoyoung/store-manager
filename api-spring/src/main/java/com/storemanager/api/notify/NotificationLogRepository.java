package com.storemanager.api.notify;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /** 미납 알림(B4) 단계별 중복발송 방지 — 같은 대상(refType/refId)에 같은 템플릿이 몇 번 발송됐는지 센다. */
    long countByRefTypeAndRefIdAndTemplate(String refType, Long refId, String template);
}
