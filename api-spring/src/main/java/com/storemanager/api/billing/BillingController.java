package com.storemanager.api.billing;

import com.storemanager.api.billing.BillingDtos.PaymentListResponse;
import com.storemanager.api.billing.BillingDtos.SubscriptionResponse;
import com.storemanager.api.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 레거시 자체 결제 조회 API. 신규 구독 시작은 Groble 결제창으로만 진행한다. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/subscription")
    public SubscriptionResponse get(@PathVariable UUID storeId) {
        return billingService.getSubscription(CurrentUser.publicId(), storeId);
    }

    @GetMapping("/payments")
    public PaymentListResponse payments(@PathVariable UUID storeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return billingService.listPayments(CurrentUser.publicId(), storeId, page, size);
    }
}
