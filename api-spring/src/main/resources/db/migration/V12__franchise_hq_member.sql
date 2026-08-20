-- 가맹본부 계정 ↔ 브랜드 매핑 (FR-801, docs/10 §2.9 / docs/11 §2.7, 2026-08-20 신설)
-- 행이 존재하면 그 사용자는 해당 브랜드(store.brand_name)의 본부 사용자다.
-- ★ app_user 에 별도 role 컬럼을 추가하지 않는다 — 본부 권한 판정은 이 테이블이 유일한 근거다.
CREATE TABLE franchise_hq_member (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    brand_name  VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_hq_member ON franchise_hq_member (user_id, brand_name);
CREATE INDEX idx_hq_brand ON franchise_hq_member (brand_name);
