-- reply_style_sample 에 플랫폼 경계를 준다 (2026-09-19).
--
-- 지금까지 이 테이블에는 platform 컬럼이 없었다. 그래서 네이버 답글 생성이 배달에서
-- 긁어온 사장님 답글(RC_LIST)을 few-shot 으로 그대로 받아, "주문 요청사항에 적어주시면…"
-- 같은 배달 특유 표현이 방문 리뷰 답글에 새고 있었다.
--
-- 기존 행은 전부 DataAPI(배달 3사) 에서 적재됐으므로 DELIVERY 로 백필하고,
-- 기존 적재 코드(CollectResultService 등)를 고치지 않아도 되도록 DEFAULT 도 DELIVERY 로 둔다.
ALTER TABLE reply_style_sample
    ADD COLUMN platform VARCHAR(20) NOT NULL DEFAULT 'DELIVERY';

CREATE INDEX idx_style_store_platform ON reply_style_sample (store_id, platform);
