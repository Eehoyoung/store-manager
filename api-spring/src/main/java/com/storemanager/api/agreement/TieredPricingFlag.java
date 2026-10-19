package com.storemanager.api.agreement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 선택형 요금 기능 플래그(app.pricing.tiered.enabled, env TIERED_PRICING_ENABLED).
 * ★ 기본 false. 문자열이 정확히 "true" 일 때만 켜진다(DATAAPI_WRITE_ENABLED 와 같은 규칙) —
 * "TRUE"·"1"·"yes" 는 꺼짐이다. 별도 빈이라 StoreServiceGate 등 기존 생성자를 건드리지 않는다.
 */
@Component
public class TieredPricingFlag {
    private final boolean enabled;

    public TieredPricingFlag(@Value("${app.pricing.tiered.enabled:false}") String raw) {
        this.enabled = "true".equals(raw);
    }

    public boolean enabled() {
        return enabled;
    }
}
