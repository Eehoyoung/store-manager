package com.storemanager.api.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "app.alimtalk.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AlimtalkDispatchTransactions.class)
@ActiveProfiles("test")
@Testcontainers
class AlimtalkDispatchTransactionsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired AlimtalkDispatchTransactions transactions;
    @Autowired NotificationLogRepository logs;
    @Autowired AppUserRepository users;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 결과기록이_롤백돼도_claim은_SENDING으로_남고_다시_잡히지_않는다() {
        AppUser user = users.save(AppUser.builder().email("claim@t.com").name("사장")
                .phone("01012345678").phoneVerifiedAt(Instant.now()).build());
        logs.save(NotificationLog.builder().userId(user.getId()).channel("ALIMTALK")
                .template("HIGH_RISK_REVIEW").status("DELIVERED").providerMessageId("duplicate-id").build());
        NotificationLog queued = logs.save(NotificationLog.builder().userId(user.getId()).channel("ALIMTALK")
                .template("HIGH_RISK_REVIEW").status("QUEUED").nextAttemptAt(Instant.now()).build());

        var claim = transactions.claimNext().orElseThrow();
        assertThat(claim.logId()).isEqualTo(queued.getId());
        assertThat(transactions.claimNext()).isEmpty();

        assertThatThrownBy(() -> transactions.recordAccepted(queued.getId(), "duplicate-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(logs.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo("SENDING");
    }
}
