package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlimtalkDispatcherTest {

    @Mock AlimtalkDispatchTransactions transactions;
    @Mock StoreRepository stores;
    @Mock SolapiSender sender;
    private AlimtalkDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AlimtalkProperties properties = new AlimtalkProperties();
        properties.setLinkBaseUrl("https://reviewpilot.example");
        dispatcher = new AlimtalkDispatcher(transactions, stores, sender, new ObjectMapper(), properties);
    }

    @Test
    void 한_건의_발송과_결과기록_실패가_다른_건을_되돌리지_않는다() throws Exception {
        var first = claim(1L, "01011111111", "{}");
        var second = claim(2L, "01022222222", "{}");
        var third = claim(3L, "01033333333", "{}");
        when(transactions.claimNext()).thenReturn(
                Optional.of(first), Optional.of(second), Optional.of(third), Optional.empty());
        when(sender.sendHighRisk(eq("01011111111"), any())).thenReturn(new SolapiSender.SendResult("m1", "2000"));
        when(sender.sendHighRisk(eq("01022222222"), any())).thenThrow(new IllegalStateException("접수 결과 불명"));
        when(sender.sendHighRisk(eq("01033333333"), any())).thenReturn(new SolapiSender.SendResult("m3", "2000"));
        doThrow(new IllegalStateException("DB 기록 실패")).when(transactions).recordAccepted(1L, "m1");

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);

        verify(transactions).recordAccepted(1L, "m1");
        verify(transactions).recordFailed(2L);
        verify(transactions).recordAccepted(3L, "m3");
    }

    @Test
    void payload의_민감키는_버리고_승인된_두_변수만_전달한다() throws Exception {
        UUID publicId = UUID.randomUUID();
        String payload = """
                {"storeName":"조작값","reviewUrl":"https://evil.example",
                 "reviewBody":"원문","authorName":"작성자","phone":"01012345678"}
                """;
        when(transactions.claimNext()).thenReturn(Optional.of(claim(1L, "01011111111", payload)), Optional.empty());
        when(stores.findById(10L)).thenReturn(Optional.of(
                Store.builder().id(10L).publicId(publicId).ownerId(1L).name("정상 매장").build()));
        when(sender.sendHighRisk(any(), any())).thenReturn(new SolapiSender.SendResult("m1", "2000"));

        dispatcher.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendHighRisk(eq("01011111111"), variables.capture());
        assertThat(variables.getValue())
                .containsOnlyKeys("storeName", "reviewUrl")
                .containsEntry("storeName", "정상 매장");
        assertThat(variables.getValue().get("reviewUrl"))
                .contains(publicId.toString())
                .doesNotContain("evil");
    }

    @Test
    void 깨진_payload도_매장명과_로그인보호_링크를_채운다() throws Exception {
        UUID publicId = UUID.randomUUID();
        when(transactions.claimNext()).thenReturn(Optional.of(claim(1L, "01011111111", "{깨짐")), Optional.empty());
        when(stores.findById(10L)).thenReturn(Optional.of(
                Store.builder().id(10L).publicId(publicId).ownerId(1L).name("정상 매장").build()));
        when(sender.sendHighRisk(any(), any())).thenReturn(new SolapiSender.SendResult("m1", "2000"));

        dispatcher.dispatchDue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendHighRisk(any(), variables.capture());
        assertThat(variables.getValue()).containsOnlyKeys("storeName", "reviewUrl");
    }

    /** ★ 실패 원인은 로그에만 남는다. 수신번호는 가리되 추적 단서는 살려야 한다. */
    @Test
    void 실패로그는_수신번호만_가리고_원인은_남긴다() {
        assertThat(AlimtalkDispatcher.maskPhones("invalid receiver 01012345678 for pfId KA01PF"))
                .isEqualTo("invalid receiver *** for pfId KA01PF");
        assertThat(AlimtalkDispatcher.maskPhones("수신번호 010-1234-5678 오류")).isEqualTo("수신번호 *** 오류");

        // 날짜·템플릿ID·메시지ID 는 원인 추적 단서라 가리지 않는다.
        assertThat(AlimtalkDispatcher.maskPhones("template TPL_2026 not approved at 2026-08-28"))
                .isEqualTo("template TPL_2026 not approved at 2026-08-28");
        assertThat(AlimtalkDispatcher.maskPhones(null)).isEmpty();
    }

    private AlimtalkDispatchTransactions.Claim claim(Long id, String phone, String payload) {
        return new AlimtalkDispatchTransactions.Claim(id, 10L, payload, phone);
    }
}
