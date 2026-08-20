package com.storemanager.api.billing;

import com.storemanager.api.billing.BillingDtos.ConfirmPaymentRequest;
import com.storemanager.api.billing.BillingDtos.ConfirmPaymentResponse;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 입금 확인 (docs/13 §9.1, B6). 은행 API 연동이 없어 운영자가 수동 대조 후 호출한다.
 * CollectResultController 와 동일하게 X-Internal-Token 공유 시크릿을 상수시간 비교로 검증한다.
 * ★ 요청 본문에 depositorName([PII])이 포함되므로 이 컨트롤러는 요청/응답을 로깅하지 않는다(B7).
 */
@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    private final BillingService billingService;
    private final String internalToken;

    public InternalPaymentController(BillingService billingService,
            @Value("${app.internal.token}") String internalToken) {
        this.billingService = billingService;
        this.internalToken = internalToken;
    }

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<ConfirmPaymentResponse> confirm(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable Long paymentId, @Valid @RequestBody ConfirmPaymentRequest req) {
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(billingService.confirmPayment(paymentId, req));
    }
}
