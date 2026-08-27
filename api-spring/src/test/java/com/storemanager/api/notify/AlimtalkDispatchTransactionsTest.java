package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlimtalkDispatchTransactionsTest {

    @Mock NotificationLogRepository logs;
    @Mock AppUserRepository users;

    @Test
    void 미인증_번호는_claim_트랜잭션에서_SKIPPED로_종결한다() {
        NotificationLog log = queued();
        AppUser user = AppUser.builder().id(1L).email("a@b.com").name("사장님")
                .phone("010-1234-5678").build();
        when(logs.findFirstByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(anyString(), anyString(), any()))
                .thenReturn(Optional.of(log));
        when(users.findById(1L)).thenReturn(Optional.of(user));

        var claim = new AlimtalkDispatchTransactions(logs, users).claimNext().orElseThrow();

        assertThat(claim.sendable()).isFalse();
        assertThat(log.getStatus()).isEqualTo("SKIPPED");
        assertThat(log.getErrorCode()).isEqualTo("PHONE_NOT_VERIFIED");
        verify(logs, never()).save(any());
    }

    @Test
    void claim은_먼저_SENDING으로_바꾸고_발송정보만_반환한다() {
        NotificationLog log = queued();
        AppUser user = AppUser.builder().id(1L).email("a@b.com").name("사장님")
                .phone("010-1234-5678").phoneVerifiedAt(Instant.now()).build();
        when(logs.findFirstByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(anyString(), anyString(), any()))
                .thenReturn(Optional.of(log));
        when(users.findById(1L)).thenReturn(Optional.of(user));

        var claim = new AlimtalkDispatchTransactions(logs, users).claimNext().orElseThrow();

        assertThat(log.getStatus()).isEqualTo("SENDING");
        assertThat(log.getAttemptCount()).isEqualTo(1);
        assertThat(claim.phone()).isEqualTo("01012345678");
    }

    private NotificationLog queued() {
        return NotificationLog.builder().id(9L).userId(1L).storeId(2L).channel("ALIMTALK")
                .template("HIGH_RISK_REVIEW").status("QUEUED").payload("{}")
                .nextAttemptAt(Instant.now()).build();
    }
}
