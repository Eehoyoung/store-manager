-- 알림 변수 저장 (2026-08-27)
--
-- 카카오 알림톡은 승인된 템플릿에 변수만 치환해 보낸다. 무엇을 채워 보냈는지 남기지 않으면
-- "왜 이 숫자가 갔나" 를 나중에 답할 수 없다. 발송 실패 시 재발송 근거도 여기서 나온다.
ALTER TABLE notification_log ADD COLUMN payload JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 하루 한 번 보내는 브리핑이 배치 재실행으로 두 번 가면 안 된다.
-- (store_id, template, 발송일) 로 중복을 구조적으로 막는다.
-- ★ "배치가 두 번 돌 리 없다" 에 의존하지 말 것 — 청구 배치에서 같은 교훈을 얻었다.
CREATE UNIQUE INDEX uq_daily_briefing_once
    ON notification_log (store_id, template, (timezone('Asia/Seoul', sent_at)::date))
    WHERE template = 'DAILY_BRIEFING';

COMMENT ON COLUMN notification_log.payload IS '알림톡 템플릿 변수. 무엇을 채워 보냈는지의 기록.';
