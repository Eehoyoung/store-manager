package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoggingNotifierTest {

    @Mock NotificationLogRepository repository;

    @Test
    void 꺼져_있으면_기록만_남기고_발송_대상으로_만들지_않는다() {
        AlimtalkProperties properties = new AlimtalkProperties();
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        new LoggingNotifier(repository, properties)
                .send(1L, 2L, "ALIMTALK", "HIGH_RISK_REVIEW", "UNIFIED_REVIEW", 3L);

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RECORDED");
        assertThat(captor.getValue().getNextAttemptAt()).isNull();
    }

    @Test
    void 켠_뒤_새_고위험_알림만_큐에_넣는다() {
        AlimtalkProperties properties = new AlimtalkProperties();
        properties.setEnabled(true);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        LoggingNotifier notifier = new LoggingNotifier(repository, properties);

        notifier.send(1L, 2L, "ALIMTALK", "HIGH_RISK_REVIEW", "UNIFIED_REVIEW", 3L);
        notifier.send(1L, 2L, "ALIMTALK", "LOW_RATING_REVIEW", "UNIFIED_REVIEW", 4L);

        verify(repository).enqueueHighRiskIfAbsent(1L, 2L, "UNIFIED_REVIEW", 3L, "{}");
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RECORDED");
    }
}
