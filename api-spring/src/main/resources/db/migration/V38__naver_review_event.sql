-- 네이버 스마트플레이스 리뷰 답글 어댑터 — 메타데이터 전용 테이블 (2026-09-19)
--
-- ★ body(리뷰 원문) 컬럼을 추가하지 말 것. 확장이 브라우저 로컬에서만 원문을 다루고
--   서버에는 review_hash(SHA-256)와 우리가 생성한 답글만 남긴다(IMPLEMENTATION_PLAN_NAVER.md §4).
--   컬럼이 없으면 코드가 실수해도 원문이 저장되지 않는다 — 코드 규칙보다 스키마가 강하다.
--
-- 기존 unified_review·reply_draft·platform_account 는 배달 3사 전용이며 이 마이그레이션은
-- 그 테이블들의 의미를 바꾸지 않는다. 네이버 경로는 완전히 격리된 신규 테이블 1개로 존재한다.

CREATE TABLE naver_review_event (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID        NOT NULL DEFAULT gen_random_uuid(),
    store_id        BIGINT      NOT NULL REFERENCES store(id),
    review_hash     VARCHAR(64) NOT NULL,   -- 확장이 만든 SHA-256 hex. 원문·닉네임 불가역
    rating          SMALLINT,
    category        VARCHAR(20),
    risk_level      SMALLINT    NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFTED'
                    CHECK (status IN ('DRAFTED','VIEWED','EDITED','APPROVED','INSERTED','POSTED','SKIPPED')),
    draft_content   TEXT,                   -- 자사 저작물(생성 답글). 리뷰 원문 아님
    guardrail_flags TEXT[]      NOT NULL DEFAULT '{}',
    blocked         BOOLEAN     NOT NULL DEFAULT FALSE,
    edited          BOOLEAN     NOT NULL DEFAULT FALSE,
    edit_distance   INTEGER,
    drafted_at      TIMESTAMPTZ,
    viewed_at       TIMESTAMPTZ,
    approved_at     TIMESTAMPTZ,
    inserted_at     TIMESTAMPTZ,
    posted_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, review_hash)
);
CREATE INDEX idx_naver_event_store_status ON naver_review_event (store_id, status);
CREATE TRIGGER trg_naver_event_updated BEFORE UPDATE ON naver_review_event
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 확장 일괄승인 PIN(BCrypt). 공용 포스 PC에서 직원이 대신 승인하는 것을 막는다(docs/naver/03 §Tier 1 성립조건).
ALTER TABLE app_user ADD COLUMN naver_bulk_pin_hash VARCHAR(72);
