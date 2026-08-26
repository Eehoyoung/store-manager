package com.storemanager.api.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 정오 브리핑 배치.
 *
 * <p>★ 정오인 이유 — 수집이 오전 10시에 끝난다(worker/celery_app.py). 두 시간이면 분석과
 * 초안 작성이 다 돌아 그날 몫이 정리돼 있고, 사장님은 점심 장사 전에 확인할 수 있다.
 * 수집 시각을 바꾸면 이 시각도 함께 봐야 한다.
 *
 * <p>다른 스케줄러와 같은 {@code @ConditionalOnProperty} 패턴 —
 * {@code app.scheduler.briefing.enabled=false} 로 끌 수 있다(테스트 프로파일은 비활성).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.briefing.enabled", havingValue = "true")
public class DailyBriefingScheduler {

    private final DailyBriefingService dailyBriefingService;

    public DailyBriefingScheduler(DailyBriefingService dailyBriefingService) {
        this.dailyBriefingService = dailyBriefingService;
    }

    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public void briefingBatch() {
        dailyBriefingService.sendDailyBriefings();
    }
}
