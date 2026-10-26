-- 미납 해지 절차(T-30, D120). 시정 요구 통지 시각·해지 확정일·해지 3일 전 재통지 시각.
-- 결제 성공·카드 교체·운영자 활성화 시 세 컬럼을 모두 비운다(Subscription.clearDunning).
ALTER TABLE subscription ADD COLUMN dunning_notified_at TIMESTAMPTZ;
ALTER TABLE subscription ADD COLUMN termination_due_date DATE;
ALTER TABLE subscription ADD COLUMN termination_reminded_at TIMESTAMPTZ;
