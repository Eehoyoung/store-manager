-- DraftScheduler 가 60초마다 도는 "초안이 없는 리뷰" 안티조인용 인덱스.
-- 기존 review_id 인덱스는 uq_active_reply/uq_published_reply 둘 다 부분 인덱스라
-- 상태 조건이 없는 NOT EXISTS 에는 쓰이지 않는다.
CREATE INDEX IF NOT EXISTS idx_draft_review ON reply_draft (review_id);
