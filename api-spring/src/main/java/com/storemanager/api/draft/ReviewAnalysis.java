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
    private String category; // PRAISE|POSITIVE|IMPROVEMENT|COMPLAINT|ABUSIVE|OFF_TOPIC|NOISE

    /** 응대 강도 CALM|DISAPPOINTED|ANGRY (v2.0). 카테고리와 따로 매긴다. */
    @Builder.Default
    @Column(nullable = false)
    private String tone = "CALM";

    @Column(nullable = false)
    private float sentiment; // -1..1

    /** ★ v2.0 부터 **문제로 지적된 태그만** 담는다. 칭찬은 praisedTags 로 간다. */
    @Builder.Default
    @Column(name = "issue_tags", nullable = false)
    private String[] issueTags = new String[0];

    @Builder.Default
    @Column(name = "praised_tags", nullable = false)
    private String[] praisedTags = new String[0];

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
    public void applyIncoming(String category, String tone, float sentiment, String[] issueTags,
            String[] praisedTags, short riskLevel,
            String[] riskReasons, String model, String promptVersion) {
        this.category = category;
        // ★ 구버전 AI 응답에는 tone 이 없다. 빠지면 가장 약한 강도로 떨어뜨린다 —
        //   강도를 과하게 잡는 것보다 약하게 잡는 쪽이 답글 사고가 적다.
        this.tone = tone == null || tone.isBlank() ? "CALM" : tone;
        this.sentiment = sentiment;
        this.issueTags = issueTags == null ? new String[0] : issueTags;
        this.praisedTags = praisedTags == null ? new String[0] : praisedTags;
        this.riskLevel = riskLevel;
        this.riskReasons = riskReasons == null ? new String[0] : riskReasons;
        this.model = model;
        this.promptVersion = promptVersion;
        this.analyzedAt = Instant.now();
    }
}
