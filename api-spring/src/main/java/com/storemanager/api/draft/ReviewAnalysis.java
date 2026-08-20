package com.storemanager.api.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * review_analysis 테이블 매핑 (docs/11 §2.4). PK = review_id (unified_review 1:1).
 * TEXT[] 컬럼은 StorePersona.bannedWords 와 동일하게 String[] 로 매핑한다(Hibernate 6 PG 배열 기본 지원).
 * ★ risk_level >= 3 판정은 이 엔티티가 아니라 호출부(DraftService, PublishScheduler)에서 절대규칙 3 으로 강제한다.
 */
@Entity
@Table(name = "review_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReviewAnalysis {

    @Id
    @Column(name = "review_id")
    private Long reviewId;

    @Column(nullable = false)
    private String category; // PRAISE|POSITIVE|IMPROVEMENT|COMPLAINT|ABUSIVE|NOISE

    @Column(nullable = false)
    private float sentiment; // -1..1

    @Builder.Default
    @Column(name = "issue_tags", nullable = false)
    private String[] issueTags = new String[0];

    @Column(name = "risk_level", nullable = false)
    private short riskLevel;

    @Builder.Default
    @Column(name = "risk_reasons", nullable = false)
    private String[] riskReasons = new String[0];

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Builder.Default
    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt = Instant.now();

    /** AI 재분석 결과로 덮어쓴다 (PK 고정 UPSERT, docs/11 §2.4). */
    public void applyIncoming(String category, float sentiment, String[] issueTags, short riskLevel,
            String[] riskReasons, String model, String promptVersion) {
        this.category = category;
        this.sentiment = sentiment;
        this.issueTags = issueTags == null ? new String[0] : issueTags;
        this.riskLevel = riskLevel;
        this.riskReasons = riskReasons == null ? new String[0] : riskReasons;
        this.model = model;
        this.promptVersion = promptVersion;
        this.analyzedAt = Instant.now();
    }
}
