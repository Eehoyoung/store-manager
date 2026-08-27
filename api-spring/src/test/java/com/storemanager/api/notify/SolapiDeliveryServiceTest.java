package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SolapiDeliveryServiceTest {

    @Mock NotificationLogRepository logs;

    @Test
    void 접수와_최종전달을_분리하고_중복_실패가_성공을_덮지_않는다() {
        NotificationLog log = NotificationLog.builder().channel("ALIMTALK").template("HIGH_RISK_REVIEW")
                .status("ACCEPTED").providerMessageId("m1").build();
        when(logs.findByProviderMessageId("m1")).thenReturn(Optional.of(log));
        SolapiDeliveryService service = new SolapiDeliveryService(logs);

        assertThat(service.apply("m1", "2000", "접수")).isTrue();
        assertThat(log.getStatus()).isEqualTo("ACCEPTED");

        assertThat(service.apply("m1", "4000", "성공")).isTrue();
        assertThat(log.getStatus()).isEqualTo("DELIVERED");
        assertThat(log.getDeliveredAt()).isNotNull();
        Instant deliveredAt = log.getDeliveredAt();

        service.apply("m1", "4000", "중복 성공");
        assertThat(log.getDeliveredAt()).isEqualTo(deliveredAt);

        service.apply("m1", "5000", "수신번호 01012345678 실패");
        assertThat(log.getStatus()).isEqualTo("DELIVERED");
        assertThat(log.getErrorMessage()).isNull();
    }
}
