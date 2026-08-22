CREATE TABLE franchise_affiliation_request (
    id              BIGSERIAL PRIMARY KEY,
    public_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    store_id        BIGINT NOT NULL REFERENCES store(id),
    join_code_id    BIGINT NOT NULL REFERENCES franchise_join_code(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    decided_by      BIGINT REFERENCES app_user(id),
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_affiliation_request_public_id ON franchise_affiliation_request(public_id);
CREATE UNIQUE INDEX uq_affiliation_request_pending_store ON franchise_affiliation_request(store_id)
    WHERE status = 'PENDING';
