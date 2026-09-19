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

/**
 * reply_style_sample 테이블 매핑 (docs/11 §2.6) — 말투 학습 RAG 코퍼스.
 * embedding(vector(1024)) 컬럼은 이 엔티티에 매핑하지 않는다. Sprint 3(임베딩) 담당이며,
 * 매핑에서 빠진 컬럼은 INSERT 시 DB 기본값(NULL)으로 남는다. TODO: Sprint 3 에서 임베딩 채우기.
 */
@Entity
@Table(name = "reply_style_sample")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReplyStyleSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "review_text", nullable = false)
    private String reviewText;

    @Column(name = "reply_text", nullable = false)
    private String replyText;

    private Short rating;

    @Builder.Default
    @Column(nullable = false)
    private String source = "RC_LIST";

    /** MANUAL 전용 슬롯 유형 THANKS|APOLOGY|GENERAL. 그 외 source 는 null. */
    @Column(name = "sample_type")
    private String sampleType;

    /** 이 예시가 붙은 리뷰의 카테고리. 없으면 null(RC_LIST 는 분석을 돌리지 않는다). */
    @Column(name = "category")
    private String category;

    /** 이 예시가 붙은 리뷰의 이슈 태그. few-shot 을 고를 때 겹침으로 정렬한다. */
    @Builder.Default
    @Column(name = "issue_tags", nullable = false)
    private String[] issueTags = new String[0];

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 같은 슬롯에 다시 적은 경우 본문만 갈아끼운다. 유형·매장은 슬롯의 정체성이라 바뀌지 않는다. */
    public void replaceManualText(String maskedReplyText) {
        this.replyText = maskedReplyText;
    }
}
