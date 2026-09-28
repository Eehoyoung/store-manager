package com.storemanager.api.billing;

import com.storemanager.api.billing.BillingDtos.AutoRenewRequest;
import com.storemanager.api.billing.BillingDtos.BillingView;
import com.storemanager.api.billing.BillingDtos.CheckoutRequest;
import com.storemanager.api.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 포트원 V2 빌링키 자동결제(docs/13 §9, 2026-09-29). 결제창 요청 값과 결제 상태를 이 한 경로로 준다. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/billing")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping
    public BillingView view(@PathVariable UUID storeId) {
        return billingService.view(CurrentUser.publicId(), storeId);
    }

    @PostMapping("/checkout")
    public BillingView checkout(@PathVariable UUID storeId, @Valid @RequestBody CheckoutRequest body,
            HttpServletRequest request) {
        return billingService.checkout(CurrentUser.publicId(), storeId, body, clientIp(request),
                request.getHeader("User-Agent"));
    }

    @PutMapping("/auto-renew")
    public BillingView autoRenew(@PathVariable UUID storeId, @RequestBody AutoRenewRequest body,
            HttpServletRequest request) {
        return billingService.setAutoRenew(CurrentUser.publicId(), storeId, body, clientIp(request),
                request.getHeader("User-Agent"));
    }

    /** 프록시가 전달한 첫 주소만 증적에 쓰며 어떤 로그에도 출력하지 않는다(AuthController 와 동일 패턴). */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        try {
            InetAddress.getByName(value);
            return value;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
