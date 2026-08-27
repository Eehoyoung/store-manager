-- 동의 증적은 변경하지 않고 사건마다 새 행을 추가한다.
CREATE TABLE user_agreement (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    store_id        BIGINT REFERENCES store(id),
    agreement_code  VARCHAR(40) NOT NULL,
    doc_version     VARCHAR(20) NOT NULL,
    agreed          BOOLEAN NOT NULL,
    agreed_at       TIMESTAMPTZ NOT NULL,
    ip              INET, -- [PII] 동의 증적 외 용도로 사용하거나 로그에 남기지 않는다.
    user_agent      VARCHAR(300),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_agreement_user_code_created
    ON user_agreement (user_id, agreement_code, created_at DESC);
CREATE INDEX idx_user_agreement_store
    ON user_agreement (store_id) WHERE store_id IS NOT NULL;
COMMENT ON COLUMN user_agreement.ip IS '[PII] 동의 증적 외 용도로 사용하거나 로그에 남기지 않는다.';

DROP TABLE contract;

COMMENT ON COLUMN store.activated_at IS '배달앱 계정 자격증명 위탁 동의 시각';
-- 전자서명을 다시 도입해도 activated_at 게이트는 유지하고, 값을 채우는 동의 경로만 늘린다.
-- 기존 activated_at 은 유지하며 과거 동의 행을 소급 생성하지 않는다.
