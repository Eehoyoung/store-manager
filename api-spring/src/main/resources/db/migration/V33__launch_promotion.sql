-- 정식 오픈 OPEN30 프로모션 귀속.
-- trial_ends_at 은 V7에서 이미 생성됐으며, 코드는 서버에서 검증한 값만 저장한다.
ALTER TABLE subscription
    ADD COLUMN promotion_code VARCHAR(32);

CREATE INDEX idx_subscription_promotion_code ON subscription (promotion_code)
    WHERE promotion_code IS NOT NULL;
