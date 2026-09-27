ALTER TABLE subscription ADD COLUMN billing_channel_key VARCHAR(100);
ALTER TABLE subscription ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE subscription ADD COLUMN next_billing_at TIMESTAMPTZ;
ALTER TABLE subscription ADD COLUMN renewal_failures INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_subscription_next_billing
    ON subscription (next_billing_at)
    WHERE auto_renew = true AND billing_key IS NOT NULL AND status <> 'CANCELED';
