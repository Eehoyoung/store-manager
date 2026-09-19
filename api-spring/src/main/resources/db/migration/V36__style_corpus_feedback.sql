-- 매장별 학습 루프 (2026-09-17).
--
-- 지금까지 코퍼스에 들어온 것은 RC_LIST(플랫폼에 이미 달려 있던 답글)와
-- MANUAL(설정 화면에 직접 적은 형식) 둘뿐이었다. 정작 신호가 가장 강한 데이터 —
-- **사장님이 AI 초안을 고쳐서 올린 최종본** — 은 reply_draft 에만 남고 버려졌다.
-- AI 가 쓴 것과 사람이 고친 것의 차이가 곧 "이 매장이 원하는 답글" 이다.

ALTER TABLE reply_style_sample DROP CONSTRAINT reply_style_sample_source_check;
ALTER TABLE reply_style_sample ADD CONSTRAINT reply_style_sample_source_check
    CHECK (source IN ('RC_LIST', 'MANUAL', 'EDITED'));

-- ★ AI 가 쓴 답글을 그대로 다시 넣지 않는다. 자기 출력을 예시로 되먹이면 문체가
--   자기 자신으로 수렴하고 오류가 증폭된다. 사람이 손댄 것만 학습한다.
--   그래서 source 에 'AI_PUBLISHED' 같은 값을 추가하지 않았다 — 일부러 없는 것이다.

-- 검색 축. 지금 few-shot 은 최신순이라 배달 지연 리뷰에 칭찬 답글이 붙는다.
-- 의미 임베딩(T-15)이 오기 전까지, 이미 계산해 둔 분류 축으로 대신 고른다.
ALTER TABLE reply_style_sample
    ADD COLUMN category   VARCHAR(20),
    ADD COLUMN issue_tags TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX idx_style_tags ON reply_style_sample USING gin (issue_tags);
CREATE INDEX idx_style_store_source ON reply_style_sample (store_id, source);
