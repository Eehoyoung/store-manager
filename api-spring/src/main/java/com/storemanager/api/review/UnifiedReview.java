package com.storemanager.api.review;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * unified_review 테이블 매핑 (docs/11 §2.4).
 * (platform, platform_review_id) 로 dedupe 하며(F-4), author_masked/author_hash 만 저장하고
 * 원본 닉네임은 저장하지 않는다(절대규칙 6).
 */
@Entity
@Table(name = "unified_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UnifiedReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(nullable = false)
    private String platform;

    @Column(name = "platform_review_id", nullable = false)
    private String platformReviewId; // DataAPI REVIEWID

    private Short rating;

    private String body;

    @Column(name = "author_masked")
    private String authorMasked; // [PII] 가명처리 결과만 저장

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "author_hash", length = 64)
    private String authorHash; // SHA-256 hex, CHAR(64)

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ordered_menus", columnDefinition = "jsonb", nullable = false)
    private String orderedMenus = "[]";

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls", columnDefinition = "jsonb", nullable = false)
    private String imageUrls = "[]";

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "platform_extra", columnDefinition = "jsonb", nullable = false)
    private String platformExtra = "{}";

    @Column(name = "review_status")
    private String reviewStatus; // DataAPI REVIEWSTATUS 원본 (의미 미확인, 그대로 보관)

    @Column(name = "written_at", nullable = false)
    private Instant writtenAt; // REVIEWDATE(yyyyMMdd) → 해당일 00:00 KST

    @Builder.Default
    @Column(name = "written_date_only", nullable = false)
    private boolean writtenDateOnly = true; // ★ 시각 정보 없음을 명시

    @Builder.Default
    @Column(name = "has_owner_reply", nullable = false)
    private boolean hasOwnerReply = false;

    @Column(name = "existing_reply")
    private String existingReply; // RC_LIST[].RCCONTENTS (말투 학습 소스)

    @Column(name = "existing_reply_id")
    private String existingReplyId; // RC_LIST[].RCID

    @Builder.Default
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt = Instant.now(); // ★ 지연 게시 기준 시각. 재수신 시에도 갱신하지 않는다

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "purge_after")
    private Instant purgeAfter;

    /**
     * 재폴링으로 같은 리뷰가 다시 오면 내용만 갱신한다.
     * collected_at 은 절대 건드리지 않는다 — 지연 게시 판정 기준 시각이므로 최초값을 유지해야 한다(F-4).
     */
    public void applyIncoming(Short rating, String body, String authorMasked, String authorHash,
            String orderedMenusJson, String imageUrlsJson, String platformExtraJson, String reviewStatus,
            Instant writtenAt, boolean hasOwnerReply, String existingReply, String existingReplyId) {
        this.rating = rating;
        this.body = body;
        this.authorMasked = authorMasked;
        this.authorHash = authorHash;
        this.orderedMenus = orderedMenusJson;
        this.imageUrls = imageUrlsJson;
        this.platformExtra = platformExtraJson;
        this.reviewStatus = reviewStatus;
        this.writtenAt = writtenAt;
        this.hasOwnerReply = hasOwnerReply;
        this.existingReply = existingReply;
        this.existingReplyId = existingReplyId;
    }
}
