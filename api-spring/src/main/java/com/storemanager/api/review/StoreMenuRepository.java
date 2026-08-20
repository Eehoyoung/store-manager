package com.storemanager.api.review;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    // menu_id 는 collect-result 계약에 없어 항상 null 로 온다 — 이 경우 menu_name 기준으로 중복을 막는다.
    // (store_id, platform, menu_id) 유니크 제약은 NULL 을 서로 다른 값으로 취급해 이 케이스를 걸러주지 못한다.
    boolean existsByStoreIdAndPlatformAndMenuIdIsNullAndMenuName(Long storeId, String platform, String menuName);
}
