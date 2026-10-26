package com.storemanager.api.store;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.billing.DowngradeCredentialCleanup;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 CRUD. GET /stores/{id}/summary 는 리뷰 테이블이 아직 없어 Sprint 1 범위 밖이라 만들지 않는다.
 */
@Service
public class StoreService {

    private final StoreRepository storeRepository;
    private final StorePersonaRepository storePersonaRepository;
    private final AppUserRepository appUserRepository;
    private final SubscriptionRepository subscriptions;
    private final DowngradeCredentialCleanup credentialCleanup;

    public StoreService(StoreRepository storeRepository, StorePersonaRepository storePersonaRepository,
            AppUserRepository appUserRepository, SubscriptionRepository subscriptions,
            DowngradeCredentialCleanup credentialCleanup) {
        this.subscriptions = subscriptions;
        this.credentialCleanup = credentialCleanup;
        this.storeRepository = storeRepository;
        this.storePersonaRepository = storePersonaRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public Store createStore(UUID ownerPublicId, CreateStoreRequest req) {
        AppUser owner = resolveUser(ownerPublicId);
        return createStore(owner, req.name(), req.category(), req.address());
    }

    /** 회원가입 트랜잭션에서 생성한 사용자와 첫 매장을 함께 저장한다. */
    @Transactional
    public Store createStore(AppUser owner, String name, String address) {
        return createStore(owner, name, null, address);
    }

    private Store createStore(AppUser owner, String name, String category, String address) {
        Store store = Store.builder()
                .ownerId(owner.getId())
                .name(name)
                .brandName(owner.getFranchiseBrandName())
                .category(category)
                .address(address)
                .build();
        storeRepository.save(store);

        // ★ 매장 생성 시 store_persona 를 기본값으로 함께 생성하고 브랜드 내 문체 분화용 seed 를 부여한다.
        StorePersona persona = StorePersona.builder()
                .storeId(store.getId())
                .personaSeed(generatePersonaSeed())
                .build();
        storePersonaRepository.save(persona);

        return store;
    }

    public List<Store> listMyStores(UUID ownerPublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        return storeRepository.findByOwnerIdAndDeletedAtIsNull(owner.getId());
    }

    public Store getStore(UUID ownerPublicId, UUID storeId) {
        return findOwnedStore(ownerPublicId, storeId);
    }

    @Transactional
    public Store updateStore(UUID ownerPublicId, UUID storeId, UpdateStoreRequest req) {
        Store store = findOwnedStore(ownerPublicId, storeId);
        store.applyUpdate(req.name(), req.category(), req.address());
        return store;
    }

    @Transactional
    public void deleteStore(UUID ownerPublicId, UUID storeId) {
        Store store = findOwnedStore(ownerPublicId, storeId);
        // T-31(D120): 자동결제가 켜진(해지 접수 안 된) 구독이 있으면 삭제를 막는다 - 삭제하면 청구와 해지 안내가 끊긴다.
        // 해지 접수 후 이용기간이 남은 매장·구독 없는 매장은 삭제할 수 있다.
        boolean autoRenewOn = subscriptions.findByStoreIdAndStatusNot(store.getId(), "CANCELED")
                .map(Subscription::isAutoRenew).orElse(false);
        if (autoRenewOn) {
            throw new ApiException(ErrorCode.STORE_HAS_ACTIVE_SUBSCRIPTION);
        }
        store.softDelete();
        // 삭제된 매장의 배달앱 자격증명을 파기한다. 다른 (삭제되지 않은) 매장이 같은 계정을 쓰면 cleanup 이 보존한다.
        credentialCleanup.onRemoved(store.getId(), new HashSet<>(Subscription.DELIVERY_PLATFORMS),
                "CREDENTIAL_REVOKED_ON_STORE_DELETE");
    }

    private Store findOwnedStore(UUID ownerPublicId, UUID storeId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            // ★ X1: 403 이 아니라 404 — 존재 여부를 흘리지 않는다(Sprint 5 B5, review/analytics/persona 와 일관).
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    /** 1 ~ Integer.MAX_VALUE 범위의 랜덤 시드 (docs/11 2.6장). SecureRandom 사용 필수. */
    private int generatePersonaSeed() {
        SecureRandom random = new SecureRandom();
        return 1 + random.nextInt(Integer.MAX_VALUE - 1);
    }
}
