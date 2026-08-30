package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 답글 생성 배치 주기가 프롬프트 캐시 TTL 안에 머무는지 잠근다.
 *
 * <p>★ 보통 스케줄 주기는 성능·부하로 정한다. 여기는 <b>원가</b>가 걸려 있다.
 * 분류 시스템 프롬프트가 캐시되므로, 주기가 TTL(5분)을 넘으면 매 주기 캐시가 만료돼
 * 재작성(1.25배) 비용이 붙는다. 캐시 읽기(0.1배)와 비교하면 입력 원가가 약 10배다.
 * 코드에는 아무 오류도 나지 않고 청구서에서만 드러난다 — 그래서 테스트로 잠근다.
 */
class DraftSchedulerCacheWindowTest {

    @Test
    @DisplayName("폴링 주기는 프롬프트 캐시 TTL 보다 짧아야 한다")
    void 폴링_주기가_캐시_TTL_안에_있다() {
        assertThat(DraftScheduler.POLL_INTERVAL_MS)
                .as("주기가 캐시 TTL(5분) 이상이면 매 주기 캐시가 만료돼 입력 원가가 약 10배가 된다")
                .isLessThan(DraftScheduler.PROMPT_CACHE_TTL_MS);
    }

    @Test
    @DisplayName("@Scheduled 가 상수를 쓰고 있어 위 검사가 실제 동작과 일치한다")
    void 스케줄_애노테이션이_상수와_일치한다() throws Exception {
        Method m = DraftScheduler.class.getMethod("generatePendingDrafts");
        Scheduled ann = m.getAnnotation(Scheduled.class);
        assertThat(ann).isNotNull();
        assertThat(ann.fixedDelay())
                .as("애노테이션에 숫자를 직접 적으면 상수만 바꿔도 실제 주기가 안 바뀐다")
                .isEqualTo(DraftScheduler.POLL_INTERVAL_MS);
    }
}
