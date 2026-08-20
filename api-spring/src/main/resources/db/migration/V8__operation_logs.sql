-- 운영 로그: 수집 작업, DataAPI 호출, LLM 사용량, 알림, 감사 로그

CREATE TABLE collection_job (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES platform_account(id),
    job_type        VARCHAR(20) NOT NULL CHECK (job_type IN ('POLL','BACKFILL')),
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    status          VARCHAR(20) NOT NULL
                    CHECK (status IN ('RUNNING','SUCCESS','FAILED','SKIPPED')),
    reviews_found   INT NOT NULL DEFAULT 0,
    reviews_new     INT NOT NULL DEFAULT 0,
    ecode           VARCHAR(64),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX idx_job_account ON collection_job (account_id, started_at DESC);

CREATE TABLE dataapi_call_log (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT REFERENCES platform_account(id),
    platform    VARCHAR(20) NOT NULL,
    endpoint    VARCHAR(40) NOT NULL,                   -- reviewManagement | CreateComment
    result      VARCHAR(10) NOT NULL,                   -- SUCCESS | FAIL
    ecode       VARCHAR(64),
    latency_ms  INT,
    billable    BOOLEAN NOT NULL DEFAULT TRUE,          -- 과금 여부 (업체 회신 후 확정)
    called_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dapi_time ON dataapi_call_log (called_at DESC);
CREATE INDEX idx_dapi_cost ON dataapi_call_log (endpoint, called_at DESC) WHERE billable;

CREATE TABLE llm_usage_log (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT REFERENCES store(id),
    draft_id    BIGINT REFERENCES reply_draft(id),
    purpose     VARCHAR(20) NOT NULL,                   -- CLASSIFY|GENERATE|EMBED|JUDGE
    tier        VARCHAR(4),
    model       VARCHAR(64) NOT NULL,
    token_in    INT NOT NULL DEFAULT 0,
    token_out   INT NOT NULL DEFAULT 0,
    cost_krw    NUMERIC(10,4) NOT NULL DEFAULT 0,
    latency_ms  INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_cost ON llm_usage_log (created_at DESC);
CREATE INDEX idx_llm_store ON llm_usage_log (store_id, created_at DESC);

CREATE TABLE notification_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES app_user(id),
    store_id    BIGINT REFERENCES store(id),
    channel     VARCHAR(20) NOT NULL,                   -- PUSH|ALIMTALK|EMAIL
    template    VARCHAR(50) NOT NULL,
    status      VARCHAR(20) NOT NULL,                   -- SENT|FAILED|READ
    ref_type    VARCHAR(30),
    ref_id      BIGINT,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 개인정보 접속기록 포함. 최소 1년 보관 (법정 요건)
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    BIGINT,
    actor_type  VARCHAR(20) NOT NULL,                   -- USER|SYSTEM|WORKER
    action      VARCHAR(50) NOT NULL,                   -- CREDENTIAL_READ|REPLY_PUBLISH|...
    target_type VARCHAR(30),
    target_id   BIGINT,
    ip          INET,
    detail      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_time ON audit_log (created_at DESC);
CREATE INDEX idx_audit_action ON audit_log (action, created_at DESC);
