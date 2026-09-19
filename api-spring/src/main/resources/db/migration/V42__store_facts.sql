-- 매장 사실(store_facts) — 사장님이 확정 입력한 정보만 답글에 쓴다.
--
-- ★ 왜 필요한가 — [절대 규칙] 8번이 "확인·확정하지 않은 조치를 약속하지 마라" 라서,
--   주차·좌석·소음·분위기·접근성·대기시간 지침이 전부 "확정하지 않은 ~를 약속하지 마라"
--   로 끝난다. 그 결과 방문 리뷰의 절반이 "확인해 보겠습니다" 한 줄로 수렴한다.
--   손님은 아무것도 얻지 못하고, 사장님은 답글이 다 똑같다고 느낀다.
--
-- ★ 여기 들어오는 값은 **사장님이 직접 확정한 사실**이다. 그래서 답글에 써도
--   절대규칙 8을 어기지 않는다 — 지어낸 것이 아니라 받아 적은 것이다.
--   이것이 이 테이블의 존재 이유 전부다. AI 가 채우게 하지 말 것.
--
-- ★ 전부 선택 입력이다. 비어 있으면 지금과 똑같이 동작한다(빈손이 기본값).
CREATE TABLE store_fact (
    store_id    BIGINT       NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    fact_key    VARCHAR(20)  NOT NULL
                CHECK (fact_key IN ('주차','대기시간','좌석','소음','접근성','영업시간','예약','포장')),
    fact_text   VARCHAR(200) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, fact_key)
);

CREATE TRIGGER trg_store_fact_updated BEFORE UPDATE ON store_fact
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE store_fact IS '사장님이 확정 입력한 매장 사실. 답글에서 인용 가능(절대규칙 8 예외 근거)';
COMMENT ON COLUMN store_fact.fact_text IS '손님에게 그대로 읽힐 문장. AI 가 생성하지 않는다.';
