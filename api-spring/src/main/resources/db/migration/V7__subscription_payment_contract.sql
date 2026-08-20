-- 구독(subscription) · 결제(payment) · 전자계약(contract)

CREATE TABLE subscription (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT NOT NULL REFERENCES store(id),
    plan_code       VARCHAR(30) NOT NULL DEFAULT 'STANDARD',
    price_krw       NUMERIC(12,2) NOT NULL DEFAULT 30000,   -- VAT 별도
    status          VARCHAR(20) NOT NULL DEFAULT 'TRIAL'
                    CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELED')),
    billing_key     VARCHAR(255),                       -- [PII] 토스 빌링키
    trial_ends_at   TIMESTAMPTZ,
    current_period_start TIMESTAMPTZ,
    current_period_end   TIMESTAMPTZ,
    commit_until    TIMESTAMPTZ,                        -- 최소 약정 종료일
    canceled_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_sub_store ON subscription (store_id)
    WHERE status <> 'CANCELED';

CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES subscription(id),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,       -- 웹훅 중복 방지
    amount_krw      NUMERIC(12,2) NOT NULL,
    vat_krw         NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED','PARTIAL_REFUNDED')),
    pg_tx_id        VARCHAR(128),
    fail_code       VARCHAR(64),
    attempt_no      SMALLINT NOT NULL DEFAULT 1,        -- dunning 회차
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_sub ON payment (subscription_id, created_at DESC);

CREATE TABLE contract (
    id              BIGSERIAL PRIMARY KEY,
    store_id        BIGINT NOT NULL REFERENCES store(id),
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    doc_version     VARCHAR(20) NOT NULL,               -- 서명한 계약서 버전
    provider        VARCHAR(30) NOT NULL DEFAULT 'MODUSIGN',
    provider_doc_id VARCHAR(128),
    status          VARCHAR(20) NOT NULL DEFAULT 'SENT'
                    CHECK (status IN ('SENT','VIEWED','SIGNED','EXPIRED','CANCELED')),
    signed_at       TIMESTAMPTZ,
    signer_ip       INET,                               -- [PII]
    pdf_path        VARCHAR(500),                       -- 자체 이중보관 경로
    audit_trail_path VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_contract_store ON contract (store_id, status);
