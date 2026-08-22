-- 외부 API 경로에 내부 BIGSERIAL을 노출하지 않기 위한 공개 식별자.
ALTER TABLE platform_account
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN password_fingerprint BYTEA NOT NULL DEFAULT decode(repeat('00', 32), 'hex');

CREATE UNIQUE INDEX uq_pf_account_public_id ON platform_account(public_id);
