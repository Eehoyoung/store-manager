package com.storemanager.api.store;

import com.storemanager.api.billing.SubscriptionRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * "이 매장에 돈 드는 작업을 해도 되는가" 를 판정하는 단 하나의 지점.
 *
 * <p>★ 왜 한곳에 모으는가 — 수집·생성·게시 세 경로가 각자 조건을 적고 있었고, 그중 어디도
 * 구독을 보지 않았다. 게이트가 흩어지면 한 곳만 고쳐지고 나머지는 조용히 새는 길이 된다.
 *
 * <p>★ 두 게이트는 서로 다른 것을 뜻한다. 섞으면 안 된다.
 * <ul>
 *   <li>{@code activated_at} — 전자계약 체결 여부. "우리가 이 매장을 다뤄도 되는가"(법적 근거)
 *   <li>구독 상태 — 요금을 내고 있는가. "이 매장에 비용을 써도 되는가"(사업적 근거)
 * </ul>
 * 계약만 있고 구독이 없으면 무료로 DataAPI 호출과 LLM 토큰을 태우게 된다.
 *
 * <p>★ fail-closed. 구독 행이 아예 없으면 서비스하지 않는다. Groble 결제 연동 전까지는
 * 운영자가 구독 상태를 직접 설정한다 — {@code activated_at} 을 지금 그렇게 다루는 것과 같다.
 * "결제 연동이 아직이니 일단 전부 허용" 으로 바꾸지 말 것. 그 순간 미납 매장이 계속 서비스된다.
 */
@Component
public class StoreServiceGate {

    /**
     * 비용을 써도 되는 구독 상태.
     * PAST_DUE(연체)·SUSPENDED(정지)·CANCELED(해지)는 제외한다 — 연체 중인 매장에 LLM 비용을
     * 계속 쓰면 못 받을 돈을 우리가 대신 내는 셈이다.
     */
    public static final Set<String> SERVICEABLE_SUBSCRIPTION_STATUSES = Set.of("TRIAL", "ACTIVE");

    /** findNeedingDraft 처럼 JPQL 안에서 같은 조건을 쓸 때를 위한 목록(순서 고정). */
    public static final List<String> SERVICEABLE_LIST = List.of("TRIAL", "ACTIVE");

    private final SubscriptionRepository subscriptionRepository;

    public StoreServiceGate(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /** 매장 자체가 다뤄도 되는 상태인가(계약·삭제·정지). 비용 판단은 포함하지 않는다. */
    public static boolean isContractActive(Store store) {
        return store != null
                && store.getDeletedAt() == null
                && "ACTIVE".equals(store.getStatus())
                && store.getActivatedAt() != null;
    }

    /** 요금을 내고 있는가. 구독 행이 없으면 false. */
    public boolean isSubscriptionServiceable(Long storeId) {
        return storeId != null
                && subscriptionRepository.findByStoreIdAndStatusNot(storeId, "CANCELED")
                        .map(sub -> SERVICEABLE_SUBSCRIPTION_STATUSES.contains(sub.getStatus()))
                        .orElse(false);
    }

    /** 수집·생성·게시가 물어야 할 질문. 계약과 구독을 모두 만족해야 한다. */
    public boolean isServiceable(Store store) {
        return isContractActive(store) && isSubscriptionServiceable(store == null ? null : store.getId());
    }
}
