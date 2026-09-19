-- 분류 축 확장 (프롬프트 v2.0, 2026-09-17).
--
-- 1) tone — 응대 강도. 이전에는 별점으로 대신했는데 별점은 대리 지표다.
--    별 5개를 주고도 화내는 손님과 별 1개를 주고도 담담한 손님을 가르지 못한다.
-- 2) praised_tags — 칭찬받은 태그. issue_tags 는 이제 '문제로 지적된 것' 만 담는다.
--    ★ 이 분리 전에는 '맛' 태그의 42% 가 칭찬이었다(코퍼스 844건 실측).
--      HQ 이상징후 레이더가 칭찬을 이슈 발생으로 세고 있었다는 뜻이다.
-- 3) OFF_TOPIC — 광고·시사 논평. ABUSIVE 에서 떼어냈다. 둘 다 답글을 만들지 않지만
--    ABUSIVE 는 사장님이 읽어야 하고 OFF_TOPIC 은 읽을 것도 없다.

ALTER TABLE review_analysis
    ADD COLUMN tone VARCHAR(16) NOT NULL DEFAULT 'CALM'
        CHECK (tone IN ('CALM','DISAPPOINTED','ANGRY')),
    ADD COLUMN praised_tags TEXT[] NOT NULL DEFAULT '{}';

-- ★ 기존 행은 전부 CALM 으로 남는다. 다시 분류하지 않는다 —
--   재분류는 리뷰 1건당 LLM 호출이라 곧 비용이고, 지난 답글은 이미 게시됐다.
ALTER TABLE review_analysis
    DROP CONSTRAINT IF EXISTS review_analysis_category_check;
ALTER TABLE review_analysis
    ADD CONSTRAINT review_analysis_category_check
    CHECK (category IN ('PRAISE','POSITIVE','IMPROVEMENT','COMPLAINT','ABUSIVE','OFF_TOPIC','NOISE'));

-- 칭찬 태그도 집계 대상이다(메뉴별 만족도·강점 분석). 이슈 태그와 같은 인덱스를 준다.
CREATE INDEX idx_analysis_praised_tags ON review_analysis USING gin (praised_tags);
