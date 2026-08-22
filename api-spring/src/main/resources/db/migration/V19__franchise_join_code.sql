-- 가맹점은 운영자가 발급한 코드로만 브랜드에 소속된다. 코드는 원문을 저장하지 않는다.
ALTER TABLE app_user ADD COLUMN franchise_brand_name VARCHAR(100);

-- 기존 사용자는 가장 먼저 만든 브랜드 매장을 기준으로 소속을 보존한다.
UPDATE app_user u
SET franchise_brand_name = first_store.brand_name
FROM (
    SELECT DISTINCT ON (owner_id) owner_id, brand_name
    FROM store
    WHERE brand_name IS NOT NULL
    ORDER BY owner_id, id
) first_store
WHERE u.id = first_store.owner_id;

CREATE TABLE franchise_join_code (
    id          BIGSERIAL PRIMARY KEY,
    brand_name  VARCHAR(100) NOT NULL,
    code_hash   CHAR(64) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_franchise_join_code_brand UNIQUE (brand_name),
    CONSTRAINT uq_franchise_join_code_hash UNIQUE (code_hash)
);
