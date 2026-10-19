-- 선택형 요금 PR2 (2026-10-19): 배달앱 선택 명단 컬럼. 추가만 한다 — 기본값·NOT NULL·백필 없음.
-- 기존 행의 NULL = '3개 상품'(현재 의미 그대로). plan_code·price_krw 의미는 바뀌지 않는다.
-- 이 컬럼을 읽는 코드는 TIERED_PRICING_ENABLED 플래그 뒤에 있다(꺼짐이면 동작 변화 없음).
--   active_delivery_platforms  : 지금 적용 중인 선택 명단 (NULL = 3개)
--   pending_delivery_platforms : 다음 결제부터 적용될 명단 (NULL = 대기 없음)
--   plan_changed_at            : 마지막 변경 요청 시각 (월 1회 판정용, NULL = 변경 이력 없음)
ALTER TABLE subscription
    ADD COLUMN active_delivery_platforms  TEXT[]      NULL,
    ADD COLUMN pending_delivery_platforms TEXT[]      NULL,
    ADD COLUMN plan_changed_at            TIMESTAMPTZ NULL;

-- 명단은 NULL 이거나 BAEMIN/COUPANGEATS/YOGIYO 중 중복 없는 1~3개.
ALTER TABLE subscription
    ADD CONSTRAINT ck_subscription_active_delivery_platforms CHECK (
        active_delivery_platforms IS NULL OR (
            active_delivery_platforms <@ ARRAY['BAEMIN','COUPANGEATS','YOGIYO']::text[]
            AND cardinality(active_delivery_platforms) BETWEEN 1 AND 3
            AND (cardinality(active_delivery_platforms) < 2
                 OR active_delivery_platforms[1] <> active_delivery_platforms[2])
            AND (cardinality(active_delivery_platforms) < 3
                 OR (active_delivery_platforms[1] <> active_delivery_platforms[3]
                     AND active_delivery_platforms[2] <> active_delivery_platforms[3]))
        )),
    ADD CONSTRAINT ck_subscription_pending_delivery_platforms CHECK (
        pending_delivery_platforms IS NULL OR (
            pending_delivery_platforms <@ ARRAY['BAEMIN','COUPANGEATS','YOGIYO']::text[]
            AND cardinality(pending_delivery_platforms) BETWEEN 1 AND 3
            AND (cardinality(pending_delivery_platforms) < 2
                 OR pending_delivery_platforms[1] <> pending_delivery_platforms[2])
            AND (cardinality(pending_delivery_platforms) < 3
                 OR (pending_delivery_platforms[1] <> pending_delivery_platforms[3]
                     AND pending_delivery_platforms[2] <> pending_delivery_platforms[3]))
        ));
