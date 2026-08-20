package com.storemanager.api.billing;

import com.storemanager.api.billing.BillingDtos.PaymentListResponse;
import com.storemanager.api.billing.BillingDtos.SubscriptionResponse;
import com.storemanager.api.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §9 구독·결제(계좌이체 전용) — 사장님용 조회/시작 API. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 구독 시작(B1). 전자계약 연동 전까지는 사장님이 명시적으로 시작한다. */
    @PostMapping("/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse create(@PathVariable UUID storeId) {
        return billingService.createSubscription(CurrentUser.publicId(), storeId);
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
