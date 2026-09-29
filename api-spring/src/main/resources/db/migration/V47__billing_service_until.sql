-- 자동결제 통일(소담한판 방식, 2026-09-29).
-- service_until = 결제예정일의 KST 날짜 + 3일 00:00(KST). 이 시각부터 이용 제한이다(유예 D+2).
-- 판정은 이 타임스탬프 비교 하나로 한다 — Java·JPQL·worker SQL 이 같은 조건을 쓴다.
-- 값은 Subscription.changeNextBillingAt 한 곳에서만 쓴다.
ALTER TABLE subscription ADD COLUMN service_until TIMESTAMPTZ;
-- 청구 직전 매장 단위 잠금(조건부 UPDATE). PG 호출은 트랜잭션 밖이라 행 잠금으로는 못 막는다.
ALTER TABLE subscription ADD COLUMN billing_lock_until TIMESTAMPTZ;

-- 기존 ACTIVE(운영자 수동 활성화)는 현재 기간 끝을 결제예정일로 본다.
UPDATE subscription SET next_billing_at = COALESCE(next_billing_at, current_period_end)
 WHERE status = 'ACTIVE';
-- 카드 없이 이미 시작된 쿠폰 체험은 체험 종료일을 결제예정일로 둔다(끝나면 카드 등록 필요).
UPDATE subscription SET next_billing_at = trial_ends_at
 WHERE status = 'TRIAL' AND promotion_code IS NOT NULL AND trial_ends_at IS NOT NULL
   AND next_billing_at IS NULL;

UPDATE subscription
   SET service_until = ((date_trunc('day', next_billing_at AT TIME ZONE 'Asia/Seoul') + INTERVAL '3 days')
                        AT TIME ZONE 'Asia/Seoul')
 WHERE next_billing_at IS NOT NULL AND status IN ('TRIAL', 'ACTIVE');

CREATE INDEX idx_subscription_service_until ON subscription (service_until)
    WHERE status NOT IN ('SUSPENDED', 'CANCELED');
