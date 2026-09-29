-- 가맹본부 시스템 콘솔·이메일 OTP 확장 (docs/26, docs/26a)
--
-- ★ 관리자는 더 이상 app_user 행을 갖지 않는다(이메일 화이트리스트 + OTP). 기존
-- /internal/franchises 로 만들어졌던 본부 계정(app_user, password_hash 있음)은 그대로 두되,
-- 이후 신규 본부 담당자는 password_hash=null 로 생성된다(app_user.password_hash 는 이미 nullable).

-- 1) franchise_brand — 브랜드 활성 상태의 정본. 기존 두 테이블의 브랜드명으로 백필한다.
CREATE TABLE franchise_brand (
    brand_name  VARCHAR(100) PRIMARY KEY,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_ref TEXT
);

INSERT INTO franchise_brand (brand_name)
SELECT DISTINCT brand_name FROM franchise_join_code
UNION
SELECT DISTINCT brand_name FROM franchise_hq_member
ON CONFLICT (brand_name) DO NOTHING;

-- 2) franchise_hq_member 확장. name 컬럼은 추가하지 않는다 — app_user.name 을 그대로 조인해 쓴다.
ALTER TABLE franchise_hq_member
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN title VARCHAR(100),
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    ADD COLUMN invited_ref TEXT,
    ADD COLUMN invited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_login_at TIMESTAMPTZ,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoked_ref TEXT;

CREATE UNIQUE INDEX uq_hq_member_public_id ON franchise_hq_member (public_id);

-- 3) franchise_join_code — 교체 이력.
ALTER TABLE franchise_join_code
    ADD COLUMN rotated_at  TIMESTAMPTZ,
    ADD COLUMN rotated_ref TEXT;

-- 4) franchise_affiliation_request — 사유·해제 상태 추가.
--    decided_by 는 V21 에서 이미 NOT NULL 제약이 없다(관리자가 app_user 를 벗어나므로 그대로 둔다).
ALTER TABLE franchise_affiliation_request
    ADD COLUMN reason      TEXT,
    ADD COLUMN released_at TIMESTAMPTZ;

ALTER TABLE franchise_affiliation_request
    DROP CONSTRAINT IF EXISTS franchise_affiliation_request_status_check;
ALTER TABLE franchise_affiliation_request
    ADD CONSTRAINT franchise_affiliation_request_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'RELEASE_REQUESTED', 'RELEASED'));

COMMENT ON TABLE franchise_brand IS '가맹본부 활성 상태 정본. SUSPENDED 면 그 브랜드의 본부 세션 접근을 즉시 막는다.';
COMMENT ON COLUMN franchise_hq_member.status IS 'ACTIVE|REVOKED. REVOKED 시 그 담당자의 본부 세션 전부를 즉시 삭제한다.';
