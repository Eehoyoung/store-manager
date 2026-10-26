package com.storemanager.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.billing.DowngradeCredentialCleanup;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StoreService 단위테스트(Sprint 5 B5, B6-d). 남의 매장 접근이 403 이 아니라 404 임을 검증한다
 * — review/persona/analytics 와의 응답코드 일관성(오케스트레이터 지시).
 */
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock private StoreRepository storeRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private SubscriptionRepository subscriptions;
    @Mock private DowngradeCredentialCleanup credentialCleanup;

    private StoreService storeService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final UUID storePublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storeService = new StoreService(storeRepository, storePersonaRepository, appUserRepository,
                subscriptions, credentialCleanup);
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    @Test
    void 남의_매장_조회는_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class, () -> storeService.getStore(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 남의_매장_수정은_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> storeService.updateStore(ownerPublicId, storePublicId,
                        new UpdateStoreRequest("새이름", null, null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 남의_매장_삭제는_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> storeService.deleteStore(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private Store myStore() {
        Store mine = Store.builder().id(10L).publicId(storePublicId).ownerId(1L).name("내가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(mine));
        return mine;
    }

    @Test
    void 자동결제가_켜진_구독이_있으면_삭제는_409다() {
        Store mine = myStore();
        Subscription sub = Subscription.builder().storeId(10L).priceKrw(java.math.BigDecimal.TEN).autoRenew(true).build();
        when(subscriptions.findByStoreIdAndStatusNot(10L, "CANCELED")).thenReturn(Optional.of(sub));

        ApiException ex = assertThrows(ApiException.class, () -> storeService.deleteStore(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STORE_HAS_ACTIVE_SUBSCRIPTION);
        assertThat(mine.getDeletedAt()).isNull();
        verify(credentialCleanup, never()).onRemoved(any(), any(), any());
    }

    @Test
    void 해지_접수된_구독이거나_구독이_없으면_삭제하고_자격증명을_파기한다() {
        Store mine = myStore();
        Subscription canceled = Subscription.builder().storeId(10L).priceKrw(java.math.BigDecimal.TEN).autoRenew(false).build();
        when(subscriptions.findByStoreIdAndStatusNot(10L, "CANCELED")).thenReturn(Optional.of(canceled));

        storeService.deleteStore(ownerPublicId, storePublicId);

        assertThat(mine.getDeletedAt()).isNotNull();
        verify(credentialCleanup).onRemoved(eq(10L), any(), eq("CREDENTIAL_REVOKED_ON_STORE_DELETE"));
    }

    @Test
    void 구독이_없는_매장도_삭제된다() {
        Store mine = myStore();
        when(subscriptions.findByStoreIdAndStatusNot(10L, "CANCELED")).thenReturn(Optional.empty());

        storeService.deleteStore(ownerPublicId, storePublicId);

        assertThat(mine.getDeletedAt()).isNotNull();
    }
}
