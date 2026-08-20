-- 가맹점주 승인/거절 없는 풀자동화 전환.
-- 위험·가드레일 차단은 유지하며, 기존 매장도 자동 게시 기본값으로 맞춘다.
ALTER TABLE store_persona ALTER COLUMN auto_publish SET DEFAULT TRUE;
UPDATE store_persona SET auto_publish = TRUE;
