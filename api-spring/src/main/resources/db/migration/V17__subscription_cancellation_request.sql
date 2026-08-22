-- Groble 공식 해지 API·웹훅 규격 수령 전, 실제 해지 완료와 로컬 요청 접수를 구분한다.
ALTER TABLE subscription
    ADD COLUMN cancellation_requested_at TIMESTAMPTZ;

COMMENT ON COLUMN subscription.cancellation_requested_at IS
    '사용자의 해지 요청 접수 시각. Groble 해지 완료 또는 subscription.status=CANCELED를 뜻하지 않는다.';
