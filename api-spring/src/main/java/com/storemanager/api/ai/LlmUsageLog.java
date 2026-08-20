package com.storemanager.api.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** llm_usage_log 테이블 매핑 (docs/11 §2.8). 원가 집계용 — draft 생성마다 1행 적재한다(S3). */
@Entity
@Table(name = "llm_usage_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LlmUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "draft_id")
    private Long draftId;

    @Column(nullable = false)
    private String purpose; // CLASSIFY|GENERATE|EMBED|JUDGE

    private String tier;

    @Column(nullable = false)
    private String model;

    @Builder.Default
    @Column(name = "token_in", nullable = false)
    private int tokenIn = 0;

    @Builder.Default
    @Column(name = "token_out", nullable = false)
    private int tokenOut = 0;

    @Builder.Default
    @Column(name = "cost_krw", nullable = false)
    private BigDecimal costKrw = BigDecimal.ZERO;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
