package com.storemanager.api.collect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** dataapi_call_log 테이블 매핑 (docs/11 §2.8). DataAPI 호출 1건마다 1행 — 과금·에러 패턴 분석용. */
@Entity
@Table(name = "dataapi_call_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DataApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId; // 매핑되지 않은 매장만 온 경우 null 일 수 있다

    @Column(nullable = false)
    private String platform;

    @Column(nullable = false)
    private String endpoint; // reviewManagement | CreateComment

    @Column(nullable = false)
    private String result; // SUCCESS | FAIL

    private String ecode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Builder.Default
    @Column(nullable = false)
    private boolean billable = true;

    @Builder.Default
    @Column(name = "called_at", nullable = false)
    private Instant calledAt = Instant.now();
}
