ALTER TABLE unified_review
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE reply_draft
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX uq_unified_review_public_id ON unified_review (public_id);
CREATE UNIQUE INDEX uq_reply_draft_public_id ON reply_draft (public_id);

DROP INDEX idx_review_store_written;
CREATE INDEX idx_review_store_written ON unified_review (store_id, written_at DESC, id DESC);
