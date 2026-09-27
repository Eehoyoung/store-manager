-- 포트원 결제창의 서버 발급 paymentId만 저장한다. 카드 정보와 빌링키는 저장하지 않는다.
ALTER TABLE payment DROP CONSTRAINT IF EXISTS payment_method_check;
ALTER TABLE payment ADD CONSTRAINT payment_method_check CHECK (method IN ('BANK_TRANSFER', 'PORTONE_CARD'));
CREATE UNIQUE INDEX uq_payment_pg_tx_id ON payment (pg_tx_id) WHERE pg_tx_id IS NOT NULL;
