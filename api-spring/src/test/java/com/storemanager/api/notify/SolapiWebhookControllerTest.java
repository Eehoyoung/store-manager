package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SolapiWebhookControllerTest {

    @Mock SolapiDeliveryService deliveryService;
    private SolapiWebhookController controller;

    @BeforeEach
    void setUp() {
        AlimtalkProperties properties = new AlimtalkProperties();
        properties.setWebhookSecret("test-secret");
        controller = new SolapiWebhookController(deliveryService, properties);
    }

    @Test
    void 공유비밀과_이벤트명이_맞는_알림톡_리포트만_반영한다() {
        var ata = new SolapiWebhookController.SingleReport(null, null, null,
                "m1", "ATA", "4000", "성공");
        var sms = new SolapiWebhookController.SingleReport(null, null, null,
                "m2", "SMS", "4000", "성공");

        var response = controller.singleReport("SINGLE-REPORT",
                "fe1bae27cb7c1fb823f496f286e78f1d2ae87734", List.of(ata, sms));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(deliveryService).apply("m1", "4000", "성공");
        verify(deliveryService, never()).apply("m2", "4000", "성공");
    }

    @Test
    void 공유비밀이_다르면_본문을_처리하지_않는다() {
        var report = new SolapiWebhookController.SingleReport(null, null, null,
                "m1", "ATA", "4000", "성공");

        var response = controller.singleReport("SINGLE-REPORT", "wrong", List.of(report));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(deliveryService, never()).apply("m1", "4000", "성공");
    }
}
