package com.storemanager.api.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
class ConsentSchemaIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;

    @Test
    void V31은_계약테이블을_제거하고_동일동의를_append할_수_있다() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('contract')", String.class)).isNull();
        jdbc.update("INSERT INTO app_user(public_id,email,name,status,created_at,updated_at) VALUES(gen_random_uuid(),'consent@test','동의테스트','ACTIVE',now(),now())");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email='consent@test'", Long.class);
        String sql = "INSERT INTO user_agreement(user_id,agreement_code,doc_version,agreed,agreed_at) VALUES(?,?,?,?,now())";
        assertThat(jdbc.update(sql, userId, AgreementService.TERMS, AgreementService.CURRENT_VERSION, true)).isOne();
        assertThat(jdbc.update(sql, userId, AgreementService.TERMS, AgreementService.CURRENT_VERSION, false)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_agreement WHERE user_id=?", Long.class, userId))
                .isEqualTo(2L);
        jdbc.update("UPDATE app_user SET status='WITHDRAWN', deleted_at=now() WHERE id=?", userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_agreement WHERE user_id=?", Long.class, userId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT col_description('user_agreement'::regclass, "
                + "(SELECT ordinal_position FROM information_schema.columns WHERE table_name='user_agreement' AND column_name='ip'))", String.class))
                .contains("[PII]");
    }
}
