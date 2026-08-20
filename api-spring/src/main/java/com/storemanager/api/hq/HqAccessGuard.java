package com.storemanager.api.hq;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ★ H6 접근 통제 핵심. 모든 HQ 엔드포인트는 이 클래스를 거쳐야 한다.
 * (1) 로그인 사용자가 요청 브랜드의 franchise_hq_member 인지 확인 — 아니면 404(브랜드 존재 여부를 흘리지 않는다).
 * (2) storeId 를 받는 요청은 그 매장의 brand_name 이 요청 브랜드와 실제로 같은지 재확인한다 —
 *     브랜드 파라미터만 믿으면 다른 브랜드 매장을 storeId 로 조회할 수 있다.
 */
@Component
public class HqAccessGuard {

    private final FranchiseHqMemberRepository hqMemberRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;

    public HqAccessGuard(FranchiseHqMemberRepository hqMemberRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository) {
        this.hqMemberRepository = hqMemberRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
    }

    public AppUser resolveUser(UUID userPublicId) {
        return appUserRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    /** 로그인 사용자가 해당 브랜드의 본부 사용자인지 확인한다. 아니면 404. */
    public AppUser requireBrandAccess(UUID userPublicId, String brandName) {
        AppUser user = resolveUser(userPublicId);
        if (!hqMemberRepository.existsByUserIdAndBrandName(user.getId(), brandName)) {
            // ★ 403 이 아니라 404 — 브랜드 존재 여부·본부 권한 여부를 흘리지 않는다(review 패키지의 X1 관례와 동일).
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return user;
    }

    /** storeId 가 실제로 이 브랜드 소속인지 재확인한다(H6-2). 아니면 404. */
    public Store requireStoreInBrand(UUID storePublicId, String brandName) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!brandName.equals(store.getBrandName())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }
}
