-- 계좌이체 청구 전용 컬럼 추가 (2026-08-20 결정: 토스 빌링키·PG 카드결제 미채택)
-- 월 사용료는 계좌이체만 받는다. 입금 확인은 운영자가 수동으로 대조한다(은행 API 연동 없음).
-- CLAUDE.md 절대규칙: 카드번호·CVC·유효기간 등 결제수단 정보를 다루는 컬럼을 추가하지 않는다.

ALTER TABLE payment
    ADD COLUMN method         VARCHAR(20) NOT NULL DEFAULT 'BANK_TRANSFER'
                               CHECK (method IN ('BANK_TRANSFER')),
    ADD COLUMN deposit_code   VARCHAR(20),                          -- 입금 대조용 고유코드(입금자명에 기재)
    ADD COLUMN depositor_name VARCHAR(60),                          -- [PII] 실제 입금자명
    ADD COLUMN confirmed_by   BIGINT REFERENCES app_user(id),       -- 확인한 운영자(app_user.id, 선택)
    ADD COLUMN due_at         TIMESTAMPTZ;                          -- 납부기한

-- deposit_code 는 PENDING 상태인 동안만 유일하면 된다 — 결제 완료 후에는 코드를 재사용해도 무방하다.
-- ★ 전역 유니크다. status='PENDING' 으로 한정하면 결제 완료 후 코드가 재사용되고,
-- 사장님이 예전 코드로 늦게 입금했을 때 운영자가 '다른 매장'의 청구서에 입금을 붙일 수 있다.
-- 입금 대조 키는 돈이 걸린 값이므로 재사용하지 않는다. 코드는 얼마든지 새로 만들면 된다.
CREATE UNIQUE INDEX uq_payment_deposit_code ON payment (deposit_code)
    WHERE deposit_code IS NOT NULL;

-- ★ 계좌이체 전환으로 죽은 컬럼이 됐다. 기존 데이터가 없어도 DROP 하지 않는다(향후 PG 재도입 대비 보존).
COMMENT ON COLUMN subscription.billing_key IS
    '미사용. 토스 빌링키 폐기(계좌이체 전환, docs/13 §9, 2026-08-20) — 값을 쓰지 말 것.';
COMMENT ON COLUMN payment.pg_tx_id IS
    '미사용. PG 카드결제 미채택(계좌이체 전용, docs/13 §9, 2026-08-20) — 값을 쓰지 말 것.';
