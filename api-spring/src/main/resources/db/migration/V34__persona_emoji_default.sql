-- 답글 이모지 기본값을 '1개 이하' → '2~3개' 로 올린다 (2026-09-17 운영자 지시).
--
-- ★ emoji_level 의 숫자는 그대로 두고 2번의 '의미' 를 바꿨다.
--   0=사용 안 함 · 1=1개 이하 · 2=**2~3개(기본)** · 3=자유
--   CHECK(0..3) 를 건드리지 않으므로 마이그레이션은 기본값과 기존 행뿐이다.
ALTER TABLE store_persona
    ALTER COLUMN emoji_level SET DEFAULT 2;

-- ★ 기존 행도 함께 올린다. 정식 오픈 전이라 emoji_level=1 인 행은 사장님이 고른 값이
--   아니라 예전 기본값이 그대로 남은 것이다. 오픈 후였다면 이 UPDATE 를 하면 안 된다
--   — 사장님이 직접 '1개 이하' 를 고른 매장을 덮어쓰게 된다.
UPDATE store_persona SET emoji_level = 2 WHERE emoji_level = 1;
