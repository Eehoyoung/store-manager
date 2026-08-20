-- 풀자동화 정책에 맞지 않는 레거시 승인 상태·설정 제거 및 중복 게시 방지.

-- 승인 대기/거절 데이터는 사람이 다시 승인할 경로가 없으므로 안전하게 차단 상태로 종결한다.
UPDATE reply_draft
SET status = 'BLOCKED',
    guardrail_flags = array_append(guardrail_flags, 'LEGACY_MANUAL_STATE')
WHERE status IN ('APPROVED', 'REJECTED');

-- 이미 생성된 활성 초안이 여러 건이면 가장 먼저 생성된 한 건만 유지하고 나머지는 차단한다.
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY review_id ORDER BY created_at, id) AS position
    FROM reply_draft
    WHERE status IN ('DRAFT', 'SCHEDULED')
)
UPDATE reply_draft d
SET status = 'BLOCKED',
    guardrail_flags = array_append(d.guardrail_flags, 'DUPLICATE_AUTOMATION_DRAFT')
FROM ranked r
WHERE d.id = r.id AND r.position > 1;

ALTER TABLE reply_draft DROP CONSTRAINT reply_draft_status_check;
ALTER TABLE reply_draft ADD CONSTRAINT reply_draft_status_check
    CHECK (status IN ('DRAFT','SCHEDULED','PUBLISHED','FAILED','BLOCKED','ALREADY_REPLIED'));
ALTER TABLE reply_draft DROP COLUMN approved_by;
ALTER TABLE reply_draft DROP COLUMN approved_at;

CREATE UNIQUE INDEX uq_active_reply ON reply_draft (review_id)
    WHERE status IN ('DRAFT', 'SCHEDULED');

ALTER TABLE store_persona DROP COLUMN auto_publish;
ALTER TABLE store_persona DROP COLUMN auto_min_rating;
ALTER TABLE store_persona DROP COLUMN auto_max_risk;
ALTER TABLE store_persona ADD CONSTRAINT ck_persona_length_range
    CHECK (length_min >= 1 AND length_min <= length_max);
ALTER TABLE store_persona ADD CONSTRAINT ck_persona_delay_nonnegative
    CHECK (delay_hours >= 0);

ALTER TABLE reply_style_sample ADD CONSTRAINT ck_style_reply_nonblank
    CHECK (source <> 'MANUAL' OR char_length(btrim(reply_text)) BETWEEN 1 AND 280);
UPDATE reply_style_sample SET source = 'RC_LIST' WHERE source = 'APPROVED';
ALTER TABLE reply_style_sample DROP CONSTRAINT reply_style_sample_source_check;
ALTER TABLE reply_style_sample ADD CONSTRAINT reply_style_sample_source_check
    CHECK (source IN ('RC_LIST', 'MANUAL'));
