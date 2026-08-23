package com.storemanager.api.review;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    // MENUID 가 없는 응답(구버전 워커·필드 누락)에만 쓴다 — 이 경우 menu_name 기준으로 중복을 막는다.
    // (store_id, platform, menu_id) 유니크 제약은 NULL 을 서로 다른 값으로 취급해 이 케이스를 걸러주지 못한다.
    boolean existsByStoreIdAndPlatformAndMenuIdIsNullAndMenuName(Long storeId, String platform, String menuName);

    // MENUID 가 있으면 이쪽이 정답이다. 메뉴명이 바뀌어도 같은 메뉴로 이어진다.
    boolean existsByStoreIdAndPlatformAndMenuId(Long storeId, String platform, String menuId);
}
