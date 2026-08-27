package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminSubscriptionServiceTest {
    @Test
    void 위탁동의가_없는_매장은_CONSENT_REQUIRED로_활성화를_거절한다() {
        StoreRepository stores = mock(StoreRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        UUID storeId = UUID.randomUUID();
        when(stores.findByPublicIdAndDeletedAtIsNull(storeId))
                .thenReturn(Optional.of(Store.builder().id(1L).publicId(storeId).ownerId(2L).name("매장").build()));
        var service = new AdminSubscriptionService(stores, subscriptions, mock(AppUserRepository.class),
                mock(AuditLogRepository.class), new ObjectMapper());

        assertThatThrownBy(() -> service.activate(storeId, "입금 확인", 3L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONSENT_REQUIRED);
        verifyNoInteractions(subscriptions);
    }
}
