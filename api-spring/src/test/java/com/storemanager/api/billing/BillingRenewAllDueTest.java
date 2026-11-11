package com.storemanager.api.billing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 정기 갱신 반복 — 한 매장의 예외가 뒤 매장의 청구를 막지 않는다. */
class BillingRenewAllDueTest {

    @Test
    void 가운데_매장이_실패해도_앞뒤_매장은_갱신한다() {
        SubscriptionRepository subs = mock(SubscriptionRepository.class);
        when(subs.findByNextBillingAtBefore(any())).thenReturn(List.of(
                Subscription.builder().storeId(1L).build(),
                Subscription.builder().storeId(2L).build(),
                Subscription.builder().storeId(3L).build()));
        BillingService service = spy(new BillingService(mock(PortOneClient.class), subs, mock(PaymentRepository.class),
                mock(com.storemanager.api.store.StoreRepository.class), mock(com.storemanager.api.user.AppUserRepository.class),
                mock(com.storemanager.api.agreement.AgreementService.class), mock(EntityManager.class),
                mock(PlatformTransactionManager.class), mock(com.storemanager.api.draft.ReplyDraftRepository.class),
                mock(com.storemanager.api.review.UnifiedReviewRepository.class),
                mock(com.storemanager.api.audit.AuditLogRepository.class), mock(com.storemanager.api.store.StoreServiceGate.class),
                mock(BrandPricingService.class), mock(com.storemanager.api.agreement.TieredPricingFlag.class), mock(com.storemanager.api.draft.PlatformSelectionDraftEffects.class),
                mock(DowngradeCredentialCleanup.class), mock(com.storemanager.api.sysauth.MailService.class),
                "https://example.test", "", 30));
        doNothing().when(service).renew(1L);
        doThrow(new IllegalStateException("x")).when(service).renew(2L);
        doNothing().when(service).renew(3L);

        service.renewAllDue();

        verify(service).renew(1L);
        verify(service).renew(2L);
        verify(service).renew(3L);
    }
}
