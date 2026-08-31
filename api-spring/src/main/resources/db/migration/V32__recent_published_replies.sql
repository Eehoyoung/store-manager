-- G7: 매장의 최근 게시 답글만 최신순으로 읽는다. 기존 마이그레이션은 변경하지 않는다.
CREATE INDEX idx_draft_recent_published ON reply_draft (store_id, published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';
