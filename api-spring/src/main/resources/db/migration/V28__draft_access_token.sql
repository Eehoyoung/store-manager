-- 알림톡 링크용 일회용 접근 토큰 (2026-08-27)
--
-- 카카오톡 인앱 브라우저에는 로그인 세션이 남지 않는다. 매번 로그인시키면 사장님은
-- 두 번째부터 링크를 누르지 않고, 그러면 위험 리뷰가 방치된다.
--
-- ★ 범위를 좁혀서 위험을 감수한다. 이 토큰으로 할 수 있는 것은 '초안 하나를 보고
--   승인하거나 거절하는 것' 뿐이다. 다른 매장·다른 리뷰·설정·결제 화면에 닿지 않는다.
--   링크가 전달·캡처되어도 잃는 것은 그 리뷰 하나의 열람이지 계정이 아니다.

CREATE TABLE draft_access_token (
    id          BIGSERIAL PRIMARY KEY,
    -- ★ 평문 토큰을 저장하지 않는다. 가맹코드와 같은 원칙 —
    --   DB 가 유출돼도 유효한 링크를 만들어 낼 수 없어야 한다.
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    draft_id    BIGINT      NOT NULL REFERENCES reply_draft (id) ON DELETE CASCADE,
    store_id    BIGINT      NOT NULL REFERENCES store (id),
    expires_at  TIMESTAMPTZ NOT NULL,
    -- 승인·거절은 한 번뿐이다. 조회는 여러 번 허용한다 —
    -- 사장님이 실수로 창을 닫았다고 링크가 죽으면 그게 더 나쁘다.
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_draft_access_token_draft ON draft_access_token (draft_id);
-- 만료분 정리 배치를 위한 인덱스.
CREATE INDEX idx_draft_access_token_expires ON draft_access_token (expires_at);

COMMENT ON TABLE draft_access_token IS
    '알림톡 링크 전용 일회용 접근 토큰. 초안 1건의 조회·승인·거절만 가능하다.';
COMMENT ON COLUMN draft_access_token.token_hash IS 'SHA-256(평문 토큰). 평문은 저장하지 않는다.';
COMMENT ON COLUMN draft_access_token.used_at IS '승인·거절이 실행된 시각. 채워지면 더 이상 액션을 받지 않는다.';
