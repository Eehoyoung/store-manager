-- 배달앱 계정 등록 시 사장님이 고른 매장을 기억한다.
--
-- 왜 필요한가: 등록 화면은 storeId 를 받아 소유권까지 검증하고도 그 값을 버렸다. 그래서
-- 첫 수집에서 플랫폼 매장(STOREID)을 발견해도 '우리 매장 중 어디인지' 를 알 수 없어
-- store_platform_link 를 만들지 못했고, 수집한 리뷰가 전부 건너뛰기 처리됐다.
--
-- store_platform_link 를 등록 시점에 만들 수는 없다 — platform_store_id 가 NOT NULL 인데
-- 그 값은 DataAPI 를 호출해야만 알 수 있기 때문이다. 그래서 의도만 계정에 남겨 둔다.
ALTER TABLE platform_account
    ADD COLUMN intended_store_id BIGINT REFERENCES store(id);

COMMENT ON COLUMN platform_account.intended_store_id IS
    '등록 시 사장님이 지정한 매장. 첫 수집에서 플랫폼 STOREID 를 발견하면 이 매장과 연결한다. 연결 후에도 근거로 남긴다.';
