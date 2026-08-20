-- 통합 리뷰(unified_review) 및 AI 분석 결과(review_analysis)

CREATE TABLE unified_review (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT NOT NULL REFERENCES store(id),
    link_id             BIGINT NOT NULL REFERENCES store_platform_link(id),
    platform            VARCHAR(20) NOT NULL,
    platform_review_id  VARCHAR(128) NOT NULL,          -- DataAPI REVIEWID

    rating              SMALLINT CHECK (rating BETWEEN 1 AND 5),
    body                TEXT,
    author_masked       VARCHAR(64),                    -- [PII] 가명처리 결과만 저장
    author_hash         CHAR(64),                        -- SHA-256(원본닉네임+salt), 동일인 식별
    ordered_menus       JSONB NOT NULL DEFAULT '[]',
    image_urls          JSONB NOT NULL DEFAULT '[]',
    platform_extra      JSONB NOT NULL DEFAULT '{}',    -- 요기요 taste/quantity, 쿠팡이츠 order_num/pck_mthd
    review_status       VARCHAR(10),                    -- DataAPI REVIEWSTATUS 원본

    written_at          TIMESTAMPTZ NOT NULL,           -- REVIEWDATE(yyyyMMdd) → 해당일 00:00 KST
    written_date_only   BOOLEAN NOT NULL DEFAULT TRUE,  -- ★ 시각 정보 없음을 명시
    has_owner_reply     BOOLEAN NOT NULL DEFAULT FALSE,
    existing_reply      TEXT,                           -- RC_LIST[].RCCONTENTS (말투 학습 소스)
    existing_reply_id   VARCHAR(64),                    -- RC_LIST[].RCID

    collected_at        TIMESTAMPTZ NOT NULL DEFAULT now(),  -- ★ 지연 게시 기준 시각
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_after         TIMESTAMPTZ,                    -- 파기 예정일 (기본 collected_at + 3년)
    UNIQUE (platform, platform_review_id)
);
CREATE INDEX idx_review_store_written ON unified_review (store_id, written_at DESC);
CREATE INDEX idx_review_pending ON unified_review (store_id)
    WHERE has_owner_reply = FALSE;
CREATE INDEX idx_review_collected ON unified_review (collected_at DESC);
CREATE INDEX idx_review_purge ON unified_review (purge_after) WHERE purge_after IS NOT NULL;
CREATE TRIGGER trg_review_updated BEFORE UPDATE ON unified_review
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE review_analysis (
    review_id       BIGINT PRIMARY KEY REFERENCES unified_review(id) ON DELETE CASCADE,
    category        VARCHAR(20) NOT NULL
                    CHECK (category IN ('PRAISE','POSITIVE','IMPROVEMENT','COMPLAINT','ABUSIVE','NOISE')),
    sentiment       REAL NOT NULL CHECK (sentiment BETWEEN -1 AND 1),
    issue_tags      TEXT[] NOT NULL DEFAULT '{}',
    risk_level      SMALLINT NOT NULL DEFAULT 0 CHECK (risk_level BETWEEN 0 AND 3),
    risk_reasons    TEXT[] NOT NULL DEFAULT '{}',       -- FOOD_POISONING|FOREIGN_OBJECT|LEGAL|HYGIENE
    model           VARCHAR(64) NOT NULL,
    prompt_version  VARCHAR(20) NOT NULL,
    analyzed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_analysis_risk ON review_analysis (risk_level) WHERE risk_level >= 2;
CREATE INDEX idx_analysis_tags ON review_analysis USING gin (issue_tags);
