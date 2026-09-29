package com.storemanager.api.hq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 가맹본부 개별 리뷰 조회 기능 플래그 (docs/26a decisions.reviewFlag).
 *
 * <p>★ 기본값 false — 법무 검토·재동의 화면이 준비되기 전에는 켜지 않는다. false 면 개별 리뷰
 * API 2종은 404, 본부 화면 메뉴, 사장님 쪽 동의 카드, 동의 기록 POST 도 전부 숨긴다/거절한다.
 */
@Component
public class HqReviewAccessProperties {

    private final boolean enabled;

    public HqReviewAccessProperties(@Value("${app.hq.review-access-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
