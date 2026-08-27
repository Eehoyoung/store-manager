package com.storemanager.api.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 알림톡 활성화 시에만 신규 QUEUED 행을 처리한다. */
@Component
@ConditionalOnProperty(name = "app.alimtalk.enabled", havingValue = "true")
class AlimtalkDispatchScheduler {

    private final AlimtalkDispatcher dispatcher;

    AlimtalkDispatchScheduler(AlimtalkDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.alimtalk.dispatch-delay-ms:10000}")
    void dispatch() {
        dispatcher.dispatchDue();
    }
}
