-- 전역 마스터 테이블: 프롬프트 버전, 금칙어

CREATE TABLE prompt_version (
    version         VARCHAR(20) PRIMARY KEY,
    purpose         VARCHAR(20) NOT NULL,               -- CLASSIFY|GENERATE
    template        TEXT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT FALSE,
    golden_score    JSONB,                              -- 골든셋 평가 결과
    activated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_prompt_active ON prompt_version (purpose) WHERE is_active;

CREATE TABLE banned_word (
    id          BIGSERIAL PRIMARY KEY,
    word        VARCHAR(100) NOT NULL,
    category    VARCHAR(30) NOT NULL,                   -- PROFANITY|COMPENSATION|POLITICS|MEDICAL|COMPETITOR
    match_type  VARCHAR(10) NOT NULL DEFAULT 'CONTAINS' CHECK (match_type IN ('EXACT','CONTAINS','REGEX')),
    scope       VARCHAR(10) NOT NULL DEFAULT 'GLOBAL',
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (word, category)
);
