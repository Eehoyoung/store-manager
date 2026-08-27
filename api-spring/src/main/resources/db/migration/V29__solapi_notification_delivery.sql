-- SOLAPI 알림톡 접수·최종 전달 상태 분리 (2026-08-27)
-- 기존 행은 그대로 둔다. next_attempt_at 이 NULL 이므로 연동 활성화 시 과거 알림이 재발송되지 않는다.
ALTER TABLE notification_log
    ADD COLUMN provider_message_id VARCHAR(100),
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN error_code VARCHAR(40),
    ADD COLUMN error_message VARCHAR(500);

CREATE UNIQUE INDEX uq_notification_provider_message
    ON notification_log (provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE INDEX idx_notification_dispatch_due
    ON notification_log (next_attempt_at, id)
    WHERE channel = 'ALIMTALK' AND status = 'QUEUED';

-- 기존 SENT 행은 건드리지 않고, 연동 도입 후 같은 원인으로 고위험 알림이 중복 생성되는 것만 막는다.
CREATE UNIQUE INDEX uq_notification_high_risk_ref
    ON notification_log (template, ref_type, ref_id)
    WHERE channel = 'ALIMTALK'
      AND template = 'HIGH_RISK_REVIEW'
      AND ref_type IS NOT NULL
      AND ref_id IS NOT NULL
      AND status IN ('QUEUED', 'SENDING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'SKIPPED');

-- 현재 휴대폰 인증 API는 미구현이다. 기존 번호를 인증된 것으로 추정하지 않고 전부 NULL 로 둔다.
ALTER TABLE app_user ADD COLUMN phone_verified_at TIMESTAMPTZ;

COMMENT ON COLUMN notification_log.provider_message_id IS 'SOLAPI가 반환한 메시지 ID. 웹훅 멱등 키.';
COMMENT ON COLUMN app_user.phone_verified_at IS '휴대폰 소유 확인 시각. NULL이면 알림톡 발송 금지.';
