-- 점주 답글 형식을 '최대 3건' 에서 '유형별 3슬롯' 으로 바꾼다 (2026-09-17).
--
-- 이전에는 아무 형식이나 3개까지 받았다. 그래서 사장님이 감사 답글만 3개 적어 두면
-- 불만 리뷰에도 감사 문체가 예시로 붙었다. 유형을 고정하면 리뷰에 맞는 예시를 고를 수 있다.
--   THANKS  칭찬 리뷰에 쓰는 형식
--   APOLOGY 불만 리뷰에 쓰는 형식
--   GENERAL 어디에도 쓸 수 있는 기본 형식

ALTER TABLE reply_style_sample
    ADD COLUMN sample_type VARCHAR(16);

ALTER TABLE reply_style_sample ADD CONSTRAINT ck_style_sample_type
    CHECK (source <> 'MANUAL' OR sample_type IN ('THANKS', 'APOLOGY', 'GENERAL'));

-- ★ 슬롯을 DB 로 강제한다. 이전에는 애플리케이션이 COUNT 를 세고 행 잠금으로 경합을
--   막았다 — 조건부 유니크 인덱스면 그 경합이 구조적으로 사라진다.
CREATE UNIQUE INDEX uq_style_manual_slot
    ON reply_style_sample (store_id, sample_type)
    WHERE source = 'MANUAL';

-- 기존 MANUAL 행은 유형을 알 수 없다. 지우지 않고 GENERAL 로 둔다 —
-- 사장님이 적은 문장이고, 어디에 쓸 형식인지는 사람이 다시 고르면 된다.
-- ★ 매장당 2건 이상이면 유니크 인덱스에 걸리므로 가장 최근 것만 GENERAL 로 올리고
--   나머지는 RC_LIST 로 강등한다(삭제하지 않는다 — 코퍼스는 핵심 자산이다).
UPDATE reply_style_sample s SET sample_type = 'GENERAL'
 WHERE s.source = 'MANUAL'
   AND s.id = (SELECT m.id FROM reply_style_sample m
                WHERE m.store_id = s.store_id AND m.source = 'MANUAL'
                ORDER BY m.created_at DESC, m.id DESC LIMIT 1);
UPDATE reply_style_sample SET source = 'RC_LIST'
 WHERE source = 'MANUAL' AND sample_type IS NULL;
