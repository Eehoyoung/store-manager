package com.storemanager.api.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest
@Testcontainers
class CollectionJobRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    CollectionJobRepository collectionJobRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 수집작업_ID는_DB에서_자동생성된다() {
        Long ownerId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user(email, name) VALUES (?, ?) RETURNING id",
                Long.class, "collection-job-test@example.com", "수집 테스트");
        Long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO platform_account(owner_id, platform, login_id, enc_password, enc_dek, kms_key_id, enc_nonce)
                VALUES (?, 'BAEMIN', ?, decode('00', 'hex'), decode('00', 'hex'), 'test-key', decode('00', 'hex'))
                RETURNING id
                """, Long.class, ownerId, "collection-job-test");

        CollectionJob saved = collectionJobRepository.saveAndFlush(CollectionJob.builder()
                .accountId(accountId)
                .jobKey("collection-job-id-test")
                .jobType("POLL")
                .startDate(LocalDate.of(2026, 9, 21))
                .endDate(LocalDate.of(2026, 9, 21))
                .status("SUCCESS")
                .build());

        assertThat(saved.getId()).isPositive();
    }
}
