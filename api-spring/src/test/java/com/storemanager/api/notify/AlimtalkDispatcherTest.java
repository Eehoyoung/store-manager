package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlimtalkDispatcherTest {

    @Mock NotificationLogRepository logs;
    @Mock AppUserRepository users;
    @Mock StoreRepository stores;
    @Mock SolapiSender sender;
    private AlimtalkDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new AlimtalkDispatcher(logs, users, stores, sender, new ObjectMapper(),
                new AlimtalkProperties());
    }

    @Test
    void 미인증_번호는_외부_호출_없이_건너뛴다() throws Exception {
        NotificationLog log = queued();
        AppUser user = AppUser.builder().id(1L).email("a@b.com").name("사장님")
                .phone("010-1234-5678").build();
        when(logs.findTop20ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(anyString(), anyString(), any()))
                .thenReturn(List.of(log));
        when(users.findById(1L)).thenReturn(Optional.of(user));

        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(log.getStatus()).isEqualTo("SKIPPED");
        assertThat(log.getErrorCode()).isEqualTo("PHONE_NOT_VERIFIED");
        verify(sender, never()).sendHighRisk(anyString(), any());
    }

    @Test
    void 인증된_번호만_접수하고_전달완료로_과장하지_않는다() throws Exception {
        NotificationLog log = queued();
        AppUser user = AppUser.builder().id(1L).email("a@b.com").name("사장님")
                .phone("010-1234-5678").phoneVerifiedAt(Instant.now()).build();
        when(logs.findTop20ByChannelAndStatusAndNextAttemptAtLessThanEqualOrderById(anyString(), anyString(), any()))
                .thenReturn(List.of(log));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        UUID storePublicId = UUID.randomUUID();
        when(stores.findById(2L)).thenReturn(Optional.of(
                Store.builder().id(2L).publicId(storePublicId).ownerId(1L).name("테스트 매장").build()));
        when(sender.sendHighRisk(anyString(), any())).thenReturn(new SolapiSender.SendResult("m1", "3000"));

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(log.getStatus()).isEqualTo("ACCEPTED");
        assertThat(log.getProviderMessageId()).isEqualTo("m1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendHighRisk(org.mockito.ArgumentMatchers.eq("01012345678"), variables.capture());
        assertThat(variables.getValue()).containsEntry("storeName", "테스트 매장");
        assertThat(variables.getValue().get("reviewUrl")).contains(storePublicId.toString()).doesNotContain("/2/");
    }

    private NotificationLog queued() {
        return NotificationLog.builder().userId(1L).storeId(2L).channel("ALIMTALK")
                .template("HIGH_RISK_REVIEW").status("QUEUED").payload("{}")
                .nextAttemptAt(Instant.now()).build();
    }
}
