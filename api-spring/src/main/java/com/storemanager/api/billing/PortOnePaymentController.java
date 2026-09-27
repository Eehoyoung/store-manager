package com.storemanager.api.billing;

import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 결제창 요청과 결제 후 공식 조회. 브라우저의 성공 응답은 구독 활성화 근거로 쓰지 않는다. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/portone")
public class PortOnePaymentController {
    private final PortOnePaymentService payments;

    public PortOnePaymentController(PortOnePaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/checkout")
    public PortOnePaymentService.Checkout checkout(@PathVariable UUID storeId) {
        return payments.begin(CurrentUser.publicId(), storeId);
    }

    @PostMapping("/billing-key")
    public PortOnePaymentService.BillingStatus registerBillingKey(@PathVariable UUID storeId,
            @Valid @RequestBody BillingKeyRequest request) {
        return payments.registerBillingKey(CurrentUser.publicId(), storeId, request.billingKey());
    }

    @PostMapping("/auto-renew/stop")
    public PortOnePaymentService.BillingStatus stopAutoRenew(@PathVariable UUID storeId) {
        return payments.stopAutoRenew(CurrentUser.publicId(), storeId);
    }

    public record BillingKeyRequest(@NotBlank @jakarta.validation.constraints.Size(max = 200) String billingKey) {}
}
