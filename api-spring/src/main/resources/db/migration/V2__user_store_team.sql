-- 사용자(app_user) · 매장(store) · 매장 참여자(team_member) 테이블

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,              -- [PII]
    password_hash   VARCHAR(255),                       -- bcrypt. 소셜 로그인 시 NULL
    name            VARCHAR(50) NOT NULL,               -- [PII]
    phone           VARCHAR(20),                        -- [PII]
    social_provider VARCHAR(20),                        -- KAKAO | NULL
    social_id       VARCHAR(128),
    biz_reg_no      VARCHAR(12),                        -- [PII] 사업자등록번호
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','SUSPENDED','WITHDRAWN')),
    marketing_agreed_at TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_user_email ON app_user (lower(email)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_user_social ON app_user (social_provider, social_id)
    WHERE social_provider IS NOT NULL;
CREATE TRIGGER trg_user_updated BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE store (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    owner_id        BIGINT NOT NULL REFERENCES app_user(id),
    name            VARCHAR(100) NOT NULL,
    brand_name      VARCHAR(100),                       -- 프랜차이즈 브랜드 (중복검사 단위)
    category        VARCHAR(50),                        -- 한식|치킨|분식|카페|중식|...
    address         VARCHAR(300),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','PAUSED','SUSPENDED','DELETED')),
    activated_at    TIMESTAMPTZ,                        -- 전자계약 서명 완료 시각
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_store_owner ON store (owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_store_brand ON store (brand_name) WHERE brand_name IS NOT NULL;
CREATE TRIGGER trg_store_updated BEFORE UPDATE ON store
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE team_member (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL CHECK (role IN ('OWNER','MANAGER','STAFF')),
    invited_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    UNIQUE (store_id, user_id)
);
