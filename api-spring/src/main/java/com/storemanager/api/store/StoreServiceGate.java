package com.storemanager.api.store;

import com.storemanager.api.agreement.TieredPricingFlag;
import com.storemanager.api.billing.SubscriptionRepository;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * "이 매장에 돈 드는 작업을 해도 되는가" 를 판정하는 단 하나의 지점.
 *
 * <p>★ 왜 한곳에 모으는가 — 수집·생성·게시 세 경로가 각자 조건을 적고 있었고, 그중 어디도
 * 구독을 보지 않았다. 게이트가 흩어지면 한 곳만 고쳐지고 나머지는 조용히 새는 길이 된다.
 *
 * <p>★ 두 게이트는 서로 다른 것을 뜻한다. 섞으면 안 된다.
 * <ul>
 *   <li>{@code activated_at} — 자격증명 위탁 동의 여부. "우리가 이 매장을 다뤄도 되는가"(법적 근거)
 *   <li>구독 상태 — 요금을 내고 있는가. "이 매장에 비용을 써도 되는가"(사업적 근거)
 * </ul>
 * 계약만 있고 구독이 없으면 무료로 DataAPI 호출과 LLM 토큰을 태우게 된다.
 *
 * <p>★ fail-closed. 구독 행이 아예 없으면 서비스하지 않는다 — {@code activated_at} 동의
 * 게이트와 별개다. 판정은 전부 {@link com.storemanager.api.billing.Subscription#isServiceableAt}
 * 에 위임한다. "결제 연동이 아직이니 일단 전부 허용" 으로 바꾸지 말 것.
 */
@Component
public class StoreServiceGate {

    private static final java.util.Set<String> DELIVERY_PLATFORMS = java.util.Set.of("BAEMIN", "COUPANGEATS", "YOGIYO");

    private final SubscriptionRepository subscriptionRepository;
    private final TieredPricingFlag tieredPricingFlag;

    /** 기존 생성자 — 선택형 요금 플래그 꺼짐과 같다(플랫폼 판정은 항상 통과). */
    public StoreServiceGate(SubscriptionRepository subscriptionRepository) {
        this(subscriptionRepository, new TieredPricingFlag("false"));
    }

    @Autowired
    public StoreServiceGate(SubscriptionRepository subscriptionRepository, TieredPricingFlag tieredPricingFlag) {
        this.subscriptionRepository = subscriptionRepository;
        this.tieredPricingFlag = tieredPricingFlag;
    }

    /** 매장 자체가 다뤄도 되는 상태인가(계약·삭제·정지). 비용 판단은 포함하지 않는다. */
    public static boolean isCredentialConsentActive(Store store) {
        return store != null
                && store.getDeletedAt() == null
                && "ACTIVE".equals(store.getStatus())
                && store.getActivatedAt() != null;
    }

    /** 요금을 내고 있는가. 구독 행이 없으면 false. */
    public boolean isSubscriptionServiceable(Long storeId) {
        return storeId != null
                && subscriptionRepository.findByStoreIdAndStatusNot(storeId, "CANCELED")
                        .map(sub -> sub.isServiceableAt(Instant.now()))
                        .orElse(false);
    }

    /** 수집·생성·게시가 물어야 할 질문. 계약과 구독을 모두 만족해야 한다. */
    public boolean isServiceable(Store store) {
        return isCredentialConsentActive(store) && isSubscriptionServiceable(store == null ? null : store.getId());
    }

    /**
     * 배달 플랫폼 단위 질문 — 매장 판정에 더해, 그 플랫폼이 회원이 고른 상품 안에 있는가(선택형 요금 D38).
     * 수집 적재·초안·승인·게시·보류 재개는 리뷰의 플랫폼을 아는 곳에서 이 오버로드를 쓴다.
     */
    public boolean isServiceable(Store store, String platform) {
        return isServiceable(store) && isPlatformSelected(store.getId(), platform);
    }

    /**
     * 판정 순서(고정, 계획서 4.2): 플래그 꺼짐 → 통과 / 정확히 NAVER → 통과(모든 상품 포함) /
     * 배달 3사가 아니면(null·알 수 없음) 차단 / 선택 명단 null → 통과(3개 상품) / 명단에 포함 → 통과(fail-closed).
     * 구독 행이 없으면 차단한다(매장 판정과 같은 fail-closed).
     */
    public boolean isPlatformSelected(Long storeId, String platform) {
        if (!tieredPricingFlag.enabled()) {
            return true;
        }
        if ("NAVER".equals(platform)) {
            return true;
        }
        if (storeId == null || platform == null || !DELIVERY_PLATFORMS.contains(platform)) {
            return false; // null·알 수 없는 플랫폼·소문자는 명단 유무와 관계없이 차단
        }
        return subscriptionRepository.findByStoreIdAndStatusNot(storeId, "CANCELED")
                .map(sub -> sub.getActiveDeliveryPlatforms() == null
                        || Arrays.asList(sub.getActiveDeliveryPlatforms()).contains(platform))
                .orElse(false);
    }
}
