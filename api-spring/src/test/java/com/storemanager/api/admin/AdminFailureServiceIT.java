package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.notify.NotificationLog;
import com.storemanager.api.notify.NotificationLogRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AdminFailureServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired AdminFailureService service;
    @Autowired AppUserRepository users;
    @Autowired StoreRepository stores;
    @Autowired NotificationLogRepository logs;

    @Test
    void 실패_스킵_두시간_정체만_민감정보_없이_조회한다() {
        AppUser owner = users.save(AppUser.builder()
                .email("alimtalk-admin@t.com").passwordHash("x").name("사장").phone("01012345678").build());
        Store store = stores.save(Store.builder().ownerId(owner.getId()).name("알림점").build());
        Instant now = Instant.now();
        save(owner, store, "FAILED", now, 1L, "E_FAIL", "m1", "비밀 리뷰 원문");
        save(owner, store, "SKIPPED", now.minusSeconds(60), 2L, "PHONE_NOT_VERIFIED", null, "01012345678");
        save(owner, store, "ACCEPTED", now.minusSeconds(3 * 3600), 3L, null, "m3", "민감 payload");
        save(owner, store, "SENDING", now.minusSeconds(2 * 3600 + 60), 4L, null, null, "민감 payload");
        save(owner, store, "DELIVERED", now.minusSeconds(4 * 3600), 5L, null, "m5", "제외");
        save(owner, store, "ACCEPTED", now, 6L, null, "m6", "제외");
        save(owner, store, "RECORDED", now.minusSeconds(4 * 3600), 7L, null, null, "제외");

        var result = service.alimtalkFailures(100);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(AdminFailureService.AlimtalkFailureRow::status)
                .containsExactlyInAnyOrder("FAILED", "SKIPPED", "ACCEPTED", "SENDING");
        assertThat(result).extracting(AdminFailureService.AlimtalkFailureRow::refId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(result.stream().filter(r -> r.refId().equals(1L)).findFirst().orElseThrow()
                .providerMessageIdPresent()).isTrue();
        assertThat(Set.of(AdminFailureService.AlimtalkFailureRow.class.getRecordComponents()).stream()
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("payload", "phone", "reviewBody", "authorName");
    }

    private void save(AppUser owner, Store store, String status, Instant sentAt, Long refId,
            String errorCode, String messageId, String payload) {
        logs.save(NotificationLog.builder()
                .userId(owner.getId()).storeId(store.getId()).channel("ALIMTALK")
                .template("HIGH_RISK_REVIEW").status(status).payload("{\"secret\":\"" + payload + "\"}")
                .refType("UNIFIED_REVIEW").refId(refId).errorCode(errorCode)
                .attemptCount(1).providerMessageId(messageId).sentAt(sentAt).build());
    }
}
