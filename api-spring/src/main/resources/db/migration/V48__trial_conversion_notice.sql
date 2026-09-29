-- 무료체험 → 유료 전환 사전고지(약관 9.4조 4항, 2026-09-29).
-- 체험 종료 7일 전 이메일 발송 성공 시각. 비어 있으면 아직 고지하지 않은 것이다 — 발송이 실패하면
-- 비워 둔 채 다음 날 다시 시도한다. 한 체험에 한 번만 보낸다.
ALTER TABLE subscription ADD COLUMN trial_notice_sent_at TIMESTAMPTZ;
