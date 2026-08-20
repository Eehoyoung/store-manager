-- 말투 학습용 RAG 코퍼스(reply_style_sample, pgvector HNSW) 및 매장 메뉴(store_menu)

CREATE TABLE reply_style_sample (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    review_text TEXT NOT NULL,
    reply_text  TEXT NOT NULL,
    rating      SMALLINT,
    source      VARCHAR(20) NOT NULL DEFAULT 'RC_LIST'
                CHECK (source IN ('RC_LIST','MANUAL','APPROVED')),
    embedding   vector(1024),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_style_store ON reply_style_sample (store_id);
CREATE INDEX idx_style_vec ON reply_style_sample
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE store_menu (
    id          BIGSERIAL PRIMARY KEY,
    store_id    BIGINT NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    platform    VARCHAR(20),
    menu_id     VARCHAR(64),                            -- DataAPI MENUID
    menu_name   VARCHAR(200) NOT NULL,                  -- MENUNM
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (store_id, platform, menu_id)
);
