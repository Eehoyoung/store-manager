package com.storemanager.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 자격증명 저장·복호화 경로를 실제 Postgres(pgvector) 위에서 검증한다.
 * Flyway 마이그레이션 V1~V10 적용 + JPA 매핑(ddl-auto=validate) 일치 여부까지 함께 확인된다.
 * 절대규칙 5: DB 에는 암호문만 남고 평문은 어디에도 저장되지 않아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CredentialServiceIT {

    // 스키마에 pgvector 확장(V1)이 필요하므로 공식 postgres 대신 pgvector 이미지를 쓴다.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    CredentialService credentialService;

    @Autowired
    PlatformAccountRepository platformAccountRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    AppUserRepository appUserRepository;

    /** platform_account.owner_id 는 app_user FK 이므로 소유자를 먼저 만든다. */
    private Long 소유자를_만든다(String email) {
        return appUserRepository.save(AppUser.builder()
                .email(email)
                .passwordHash("dummy")
                .name("테스트사장")
                .build()).getId();
    }

    @Test
    void 자격증명은_암호문으로만_저장되고_복호화로_원문을_되찾는다() {
        String rawPassword = "s3cret-pw-!@#";

        PlatformAccount saved = credentialService.save(소유자를_만든다("owner1@example.com"), "BAEMIN", "mystore01", rawPassword);

        PlatformAccount reloaded = platformAccountRepository.findById(saved.getId()).orElseThrow();
        // DB 에 평문이 그대로 들어가 있지 않아야 한다.
        assertThat(new String(reloaded.getEncPassword(), StandardCharsets.UTF_8)).doesNotContain(rawPassword);
        assertThat(reloaded.getEncDek()).isNotEmpty();
        assertThat(reloaded.getEncNonce()).hasSize(12);
        assertThat(reloaded.getLinkStatus()).isEqualTo("PENDING");

        assertThat(credentialService.loadPassword(saved.getId())).isEqualTo(rawPassword);
    }

    @Test
    void 복호화할_때마다_감사로그에_CREDENTIAL_READ_가_남는다() {
        PlatformAccount saved = credentialService.save(소유자를_만든다("owner2@example.com"), "YOGIYO", "mystore02", "pw");

        credentialService.loadPassword(saved.getId());

        List<AuditLog> logs = auditLogRepository.findAll().stream()
                .filter(l -> "CREDENTIAL_READ".equals(l.getAction()))
                .filter(l -> saved.getId().equals(l.getTargetId()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetType()).isEqualTo("PLATFORM_ACCOUNT");
    }

    @Test
    void 감사로그_ip_는_inet_컬럼에_저장된다() {
        // ip 는 INET 컬럼이라 JDBC 문자열 바인드 시 캐스트가 필요하다(@ColumnTransformer 검증).
        AuditLog saved = auditLogRepository.save(AuditLog.builder()
                .actorType("USER")
                .action("LOGIN")
                .ip("203.0.113.7")
                .build());

        assertThat(auditLogRepository.findById(saved.getId()).orElseThrow().getIp())
                .isEqualTo("203.0.113.7");
    }
}
