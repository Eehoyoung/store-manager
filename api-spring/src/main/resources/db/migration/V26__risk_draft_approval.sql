-- 위험 리뷰 초안의 사람 승인 경로 복원 (2026-08-27)
--
-- 배경: V14 에서 풀자동화로 전환하며 승인 컬럼을 지웠다. 그때는 위험 리뷰를 BLOCKED 로
-- 남기고 끝냈다 — 사장님은 식중독 리뷰 앞에서 백지를 마주했다.
--
-- 이제 위험 리뷰에도 권장 답글을 만들어 두고, 사람이 승인하거나 고쳐서 게시한다.
-- ★ 절대규칙 3 은 그대로다. "자동 게시 금지"이지 "생성 금지"가 아니었고,
--   사람이 승인하는 것이 곧 검수다. 자동 경로는 여전히 risk>=3 을 통과시키지 않는다.

ALTER TABLE reply_draft ADD COLUMN approved_by BIGINT REFERENCES app_user (id);
ALTER TABLE reply_draft ADD COLUMN approved_at TIMESTAMPTZ;

-- ★ 차단 사유를 읽었다는 확인 시각. 이 값이 없으면 게시하지 않는다.
--   '읽지 않고 승인' 을 막는 유일한 증거이므로 approved_at 과 따로 둔다 —
--   한 컬럼으로 합치면 UI 가 체크박스를 건너뛰어도 서버가 알 수 없다.
ALTER TABLE reply_draft ADD COLUMN risk_ack_at TIMESTAMPTZ;

-- 사람이 고쳐 쓴 경우 원문을 남긴다. 무엇을 고쳤는지가 말투 학습과 품질 개선의 근거다.
ALTER TABLE reply_draft ADD COLUMN original_content TEXT;

COMMENT ON COLUMN reply_draft.approved_by IS '위험 초안을 승인한 사용자. NULL 이면 자동 경로로 예약된 건이다.';
COMMENT ON COLUMN reply_draft.risk_ack_at IS '차단 사유 확인 시각. risk>=3 초안은 이 값이 있어야만 게시된다.';
COMMENT ON COLUMN reply_draft.original_content IS '사람이 수정하기 전 AI 초안 원문.';

-- 승인 대기 화면이 매장별로 BLOCKED 를 훑는다. 그 조회를 위한 부분 인덱스.
CREATE INDEX idx_draft_blocked_by_store ON reply_draft (store_id, created_at DESC)
    WHERE status = 'BLOCKED';

-- ★ uq_active_reply 는 (DRAFT, SCHEDULED) 만 덮는다. BLOCKED 초안이 승인되어 SCHEDULED 로
--   가는 순간 같은 review_id 에 활성 초안이 둘이 되면 이 인덱스가 막아 준다. 그대로 둔다.
