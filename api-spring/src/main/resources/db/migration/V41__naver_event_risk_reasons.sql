-- 네이버 방문 리뷰: 위험 '사유' 를 이벤트에 남긴다.
--
-- ★ 왜 riskLevel 만으로 부족한가 — 사장님 화면에 "위험도 3" 이라고만 뜨면 무엇을
--   조심하라는 것인지 알 수 없다. 위생 지적과 협박은 사장님이 할 일이 완전히 다르다.
--   배달은 사람 검수 큐(review_analysis.risk_reasons)에 이미 사유가 남는데,
--   네이버는 ReplyDraft·ReviewAnalysis 행을 만들지 않으므로(NAVER ABSOLUTE RULE 9)
--   여기에 따로 둔다.
--
-- ★ 값은 prompts.RISK_REASON_VALUES 9종이다. 정본은 ai-python/prompts.py 이고
--   여기에 CHECK 제약을 걸지 않는다 — 사유가 늘 때 마이그레이션이 정본을 따라다니면
--   두 곳이 갈라진다(CLAUDE.md '룰이 갈라지면' 절).
ALTER TABLE naver_review_event ADD COLUMN risk_reasons TEXT[] NOT NULL DEFAULT '{}';

-- 위험 건만 훑는 조회를 위한 부분 인덱스. 대다수 행은 risk_level 0~1 이다.
CREATE INDEX idx_naver_event_risky ON naver_review_event (store_id, risk_level)
    WHERE risk_level >= 2;
