-- 플랫폼 계정(KMS 봉투암호화, 평문 비밀번호 컬럼 없음) 및 매장-플랫폼 연결

-- 1계정 N매장 구조 (DataAPI REVIEWLIST[] 대응)
CREATE TABLE platform_account (
    id              BIGSERIAL PRIMARY KEY,
    owner_id        BIGINT NOT NULL REFERENCES app_user(id),
    platform        VARCHAR(20) NOT NULL
                    CHECK (platform IN ('BAEMIN','YOGIYO','COUPANGEATS')),
    login_id        VARCHAR(128) NOT NULL,              -- [PII]

    -- KMS 봉투암호화: 평문 비밀번호 컬럼은 존재하지 않는다
    enc_password    BYTEA NOT NULL,                     -- [PII] DEK로 암호화된 암호문
    enc_dek         BYTEA NOT NULL,                     -- KMS 마스터키로 암호화된 DEK
    kms_key_id      VARCHAR(128) NOT NULL,
    enc_algorithm   VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
    enc_nonce       BYTEA NOT NULL,

    link_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (link_status IN ('PENDING','LINKED','ERROR','EXPIRED','REVOKED')),
    last_error_code VARCHAR(64),
    last_error_at   TIMESTAMPTZ,
    last_synced_at  TIMESTAMPTZ,
    verified_at     TIMESTAMPTZ,                        -- 최초 검증 성공 시각
    revoked_at      TIMESTAMPTZ,                        -- 위임 철회 → 즉시 파기
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_pf_account ON platform_account (platform, login_id)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_pf_account_sync ON platform_account (link_status, last_synced_at);
CREATE TRIGGER trg_pfacct_updated BEFORE UPDATE ON platform_account
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE store_platform_link (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    account_id          BIGINT NOT NULL REFERENCES platform_account(id) ON DELETE CASCADE,
    platform            VARCHAR(20) NOT NULL,
    platform_store_id   VARCHAR(64) NOT NULL,           -- DataAPI STOREID
    store_name_snapshot VARCHAR(200),                   -- STORENAME
    avg_rating          NUMERIC(2,1),                   -- AVGEVALUE
    backfilled_at       TIMESTAMPTZ,                    -- 90일 백필 완료 시각
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, platform_store_id)
);
CREATE INDEX idx_spl_store ON store_platform_link (store_id);
CREATE INDEX idx_spl_account ON store_platform_link (account_id);
