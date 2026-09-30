-- 가맹 브랜드 구간 단가(차등 가격제, 2026-09-30 운영자 결정).
--
-- 브랜드 소속 유료 이용 매장 수가 많을수록 매장당 월 단가가 낮아진다(PricingTier, 부가세 별도):
-- 1~49=30,000 / 50~99=29,000 / 100~199=27,000 / 200~299=26,000 / 300~399=25,000 /
-- 400~499=24,000 / 500~=23,000.
--
-- ★ committed_store_count 는 계약상 약정한 매장 수(예: "50매장 규모로 계약") — 실제 유료 매장
--   수가 아직 그 규모에 못 미쳐도 약정 단가를 먼저 적용할 때 쓴다. NULL 이면 약정이 없다는 뜻이고,
--   그러면 기본 단가(30,000원)를 쓴다(BrandPricingService).
ALTER TABLE franchise_brand
    ADD COLUMN committed_store_count INT CHECK (committed_store_count > 0);

-- ★ 매월 25일 00:05(KST) 스냅샷이 '다음 달' 단가를 확정해 담는다. 확정 이후에는 매장 수가
--   늘거나 줄어도 그 달 단가는 바뀌지 않는다 — 청구 중간에 단가가 흔들리면 안 되기 때문이다.
--   유료 매장이 0인 브랜드는 행을 만들지 않는다(약정 단가를 계속 쓴다).
CREATE TABLE brand_monthly_price (
    brand_name       TEXT        NOT NULL REFERENCES franchise_brand(brand_name),
    billing_month    DATE        NOT NULL, -- 해당 월 1일
    paid_store_count INT         NOT NULL,
    unit_price       INT         NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_at      TIMESTAMPTZ, -- 단가 변경 안내 메일 발송(또는 무변경 확인) 시각. NULL이면 미처리.
    PRIMARY KEY (brand_name, billing_month)
);

COMMENT ON TABLE brand_monthly_price IS
    '가맹 브랜드 구간 단가 스냅샷. 매월 25일 확정, 그 달 청구는 이 행의 unit_price 를 따른다.';
COMMENT ON COLUMN franchise_brand.committed_store_count IS
    '계약상 약정 매장 수. NULL이면 약정 없음 — 기본 단가(30,000원)를 쓴다.';
