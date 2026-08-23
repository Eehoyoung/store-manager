-- MENUID 를 받기 전에 이름만으로 적재된 store_menu 행을 정리한다 (T-8 후속).
--
-- 같은 (store_id, platform, menu_name) 에 대해 menu_id 가 채워진 행이 새로 생기면서
-- menu_id IS NULL 인 옛 행이 중복으로 남는다. 그대로 두면 메뉴 목록과 메뉴별 통계가
-- 같은 메뉴를 둘로 센다.
--
-- menu_id 가 있는 행이 존재하는 경우에만 지운다 — 아직 MENUID 를 못 받은 메뉴는 건드리지 않는다.
DELETE FROM store_menu old
 WHERE old.menu_id IS NULL
   AND EXISTS (
        SELECT 1 FROM store_menu cur
         WHERE cur.menu_id IS NOT NULL
           AND cur.store_id = old.store_id
           AND cur.menu_name = old.menu_name
           AND cur.platform IS NOT DISTINCT FROM old.platform
   );
