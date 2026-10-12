package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 점검표의 네이티브 SQL 이 실제 스키마(Flyway 전체 적용)에서 돈다는 것만 확인한다.
 * ★ 컬럼명이 틀리면 운영 첫 호출에서 500 이 난다 — 판정 규칙은 ReadinessEvaluatorTest 가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ReadinessServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @MockitoBean StringRedisTemplate redis;

    @Autowired ReadinessService service;

    @Test
    void 빈_DB에서도_모든_조회가_돌고_워커_신호가_없으면_알_수_없음이다() {
        var report = service.report();

        assertThat(report.items()).isNotEmpty();
        assertThat(report.mode()).isEqualTo("unknown");
        assertThat(report.items()).filteredOn(i -> i.id().equals("workerAlive"))
                .singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("UNKNOWN"));
    }
}
