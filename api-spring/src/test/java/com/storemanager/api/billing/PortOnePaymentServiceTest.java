package com.storemanager.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PortOnePaymentServiceTest {
    @Test
    void 포트원_응답_금액이_다르면_구독을_활성화하지_않는다() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments/", exchange -> {
            byte[] bytes = "{\"status\":\"PAID\",\"storeId\":\"store-test\",\"currency\":\"KRW\",\"amount\":{\"total\":1},\"channel\":{\"key\":\"inicis-test\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            UUID ownerId = UUID.randomUUID(), storeId = UUID.randomUUID();
            var users = mock(AppUserRepository.class);
            var stores = mock(StoreRepository.class);
            var subscriptions = mock(SubscriptionRepository.class);
            var payments = mock(PaymentRepository.class);
            var owner = mock(AppUser.class);
            when(owner.getId()).thenReturn(1L);
            when(users.findByPublicIdAndDeletedAtIsNull(ownerId)).thenReturn(Optional.of(owner));
            when(stores.findByPublicIdAndDeletedAtIsNull(storeId))
                    .thenReturn(Optional.of(Store.builder().id(10L).ownerId(1L).name("매장").build()));
            when(subscriptions.findByStoreIdAndStatusNot(10L, "CANCELED"))
                    .thenReturn(Optional.of(Subscription.builder().id(20L).storeId(10L).build()));
            Payment payment = Payment.builder().subscriptionId(20L).pgTxId("review-test")
                    .amountKrw(java.math.BigDecimal.valueOf(30000))
                    .vatKrw(java.math.BigDecimal.valueOf(3000)).method("PORTONE_CARD").build();
            when(payments.findByPgTxId("review-test")).thenReturn(Optional.of(payment));
            var service = new PortOnePaymentService("secret", "store-test", "inicis-test",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    HttpClient.newHttpClient(), users, stores, subscriptions, payments,
                    mock(PlatformTransactionManager.class));
            ApiException error = assertThrows(ApiException.class,
                    () -> service.verifyProvider(ownerId, storeId, "review-test"));
            assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
            assertEquals("PENDING", payment.getStatus());
        } finally {
            server.stop(0);
        }
    }
}
