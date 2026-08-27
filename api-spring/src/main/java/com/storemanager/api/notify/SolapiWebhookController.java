package com.storemanager.api.notify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SOLAPI SINGLE-REPORT 웹훅. 공유 비밀 검증 뒤 최종 전달 상태만 반영한다. */
@RestController
@RequestMapping("/internal/solapi/webhooks")
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class SolapiWebhookController {

    private final SolapiDeliveryService deliveryService;
    private final byte[] expectedSecret;

    SolapiWebhookController(SolapiDeliveryService deliveryService, AlimtalkProperties properties) {
        this.deliveryService = deliveryService;
        this.expectedSecret = sha1(properties.getWebhookSecret());
    }

    @PostMapping("/single-report")
    ResponseEntity<Void> singleReport(
            @RequestHeader(value = "X-Solapi-Event-Name", required = false) String eventName,
            @RequestHeader(value = "X-Solapi-Secret", required = false) String secret,
            @RequestBody List<SingleReport> reports) {
        if (!"SINGLE-REPORT".equals(eventName) || !matches(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (reports == null || reports.isEmpty() || reports.size() > 100) {
            return ResponseEntity.badRequest().build();
        }
        reports.stream().map(SingleReport::effectiveData).filter(java.util.Objects::nonNull)
                .filter(data -> "ATA".equals(data.type()) && data.messageId() != null)
                .forEach(data -> deliveryService.apply(data.messageId(), data.statusCode(), data.statusMessage()));
        return ResponseEntity.ok().build();
    }

    private boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                expectedSecret, candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha1(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM이 SHA-1을 지원하지 않습니다.", e);
        }
    }

    /** 최신 평면 payload와 SDK의 event data envelope를 모두 허용한다. */
    record SingleReport(String eventDataId, ReportData data, Integer retryCount,
            String messageId, String type, String statusCode, String statusMessage) {
        ReportData effectiveData() {
            return data != null ? data : new ReportData(messageId, type, statusCode, statusMessage);
        }
    }

    record ReportData(String messageId, String type, String statusCode, String statusMessage) {
    }
}
