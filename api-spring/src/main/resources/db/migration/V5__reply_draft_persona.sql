-- 답글 초안(reply_draft) 및 매장 페르소나(store_persona)

CREATE TABLE reply_draft (
    id                  BIGSERIAL PRIMARY KEY,
    review_id           BIGINT NOT NULL REFERENCES unified_review(id),
    store_id            BIGINT NOT NULL REFERENCES store(id),
    content             TEXT NOT NULL CHECK (char_length(content) <= 280),
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','APPROVED','SCHEDULED','PUBLISHED',
                                          'FAILED','REJECTED','BLOCKED','ALREADY_REPLIED')),
    generated_by        VARCHAR(20) NOT NULL
                        CHECK (generated_by IN ('AI','HUMAN','AI_EDITED','TEMPLATE')),
    model               VARCHAR(64),
    prompt_version      VARCHAR(20),
    tier                VARCHAR(4),                     -- T0|T1|T2|T3
    token_in            INT,
    token_out           INT,
    cost_krw            NUMERIC(10,4),
    guardrail_flags     TEXT[] NOT NULL DEFAULT '{}',   -- 차단 사유
    similarity_max      REAL,                           -- 최근 답글 대비 최대 유사도

    scheduled_at        TIMESTAMPTZ,
    published_at        TIMESTAMPTZ,
    platform_comment_id VARCHAR(64),                    -- DataAPI REVIEWCOMMENTID
    approved_by         BIGINT REFERENCES app_user(id),
    approved_at         TIMESTAMPTZ,
    fail_code           VARCHAR(64),
    fail_reason         TEXT,
    retry_count         SMALLINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 리뷰 1건당 게시 완료 답글은 최대 1개 (플랫폼 제약과 일치)
CREATE UNIQUE INDEX uq_published_reply ON reply_draft (review_id)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_draft_queue ON reply_draft (store_id, status, created_at DESC);
CREATE INDEX idx_draft_scheduled ON reply_draft (scheduled_at)
    WHERE status = 'SCHEDULED';
CREATE TRIGGER trg_draft_updated BEFORE UPDATE ON reply_draft
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE store_persona (
    store_id        BIGINT PRIMARY KEY REFERENCES store(id) ON DELETE CASCADE,
    tone            VARCHAR(20) NOT NULL DEFAULT 'POLITE'
                    CHECK (tone IN ('POLITE','FRIENDLY','CHEERFUL','CONCISE')),
    use_emoji       BOOLEAN NOT NULL DEFAULT TRUE,
    emoji_level     SMALLINT NOT NULL DEFAULT 1 CHECK (emoji_level BETWEEN 0 AND 3),
    customer_title  VARCHAR(20) NOT NULL DEFAULT '고객님',
    signature       VARCHAR(100),
    opening_style   VARCHAR(100),
    banned_words    TEXT[] NOT NULL DEFAULT '{}',
    length_min      SMALLINT NOT NULL DEFAULT 60,
    length_max      SMALLINT NOT NULL DEFAULT 150 CHECK (length_max <= 280),

    auto_publish    BOOLEAN NOT NULL DEFAULT FALSE,
    auto_min_rating SMALLINT NOT NULL DEFAULT 4,
    auto_max_risk   SMALLINT NOT NULL DEFAULT 1,        -- risk_level 이하만 자동
    delay_hours     SMALLINT NOT NULL DEFAULT 6,        -- collected_at 기준
    publish_windows JSONB NOT NULL DEFAULT '[]',        -- [{"start":"10:00","end":"11:30"}]
    persona_seed    INT NOT NULL,                       -- ★ 브랜드 내 문체 분화용
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
