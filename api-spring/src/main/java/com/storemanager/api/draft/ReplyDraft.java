package com.storemanager.api.draft;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * reply_draft 테이블 매핑 (docs/11 §2.5).
 * ★ 상태 전이(CLAUDE.md 상태 머신 절)를 이 클래스 하나에 모은다 — 컨트롤러·스케줄러·collect-result 에서
 * 상태값을 직접 대입하지 않고 반드시 이 메서드들을 통해서만 바꾼다(S4).
 *
 * 허용 전이: DRAFT→SCHEDULED→PUBLISHED / FAILED(재시도큐) / ALREADY_REPLIED
 *            DRAFT→BLOCKED(가드레일·자동화 불가)
 * SCHEDULED→BLOCKED 는 게시 스케줄러의 방어적 이중검증(S9)에서만 발생한다.
 */
@Entity
@Table(name = "reply_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReplyDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 280)
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "generated_by", nullable = false)
    private String generatedBy; // AI|HUMAN|AI_EDITED|TEMPLATE

    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    private String tier; // T0|T1|T2|T3

    @Column(name = "token_in")
    private Integer tokenIn;

    @Column(name = "token_out")
    private Integer tokenOut;

    @Column(name = "cost_krw")
    private BigDecimal costKrw;

    @Builder.Default
    @Column(name = "guardrail_flags", nullable = false)
    private String[] guardrailFlags = new String[0];

    @Column(name = "similarity_max")
    private Float similarityMax;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "platform_comment_id")
    private String platformCommentId;

    @Column(name = "fail_code")
    private String failCode;

    @Column(name = "fail_reason")
    private String failReason;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private short retryCount = 0;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** DRAFT → SCHEDULED. 안전 검사를 통과한 풀자동 예약에서만 호출한다. */
    public void scheduleAutomatically(Instant scheduledAt) {
        requireStatus("DRAFT");
        this.status = "SCHEDULED";
        this.scheduledAt = scheduledAt;
    }

    /** 생성 결과가 위험하거나 가드레일 플래그를 포함하면 게시 불가 상태로 종결한다. */
    public void blockForGeneration(List<String> flags) {
        requireStatus("DRAFT");
        this.status = "BLOCKED";
        this.guardrailFlags = flags == null || flags.isEmpty()
                ? new String[] {"AUTOMATION_SAFETY_BLOCKED"}
                : flags.toArray(new String[0]);
    }

    /** 게시 스케줄러의 방어적 이중검증(절대규칙 3, S9)에서 risk_level>=3 이 재확인되면 발행 직전 되돌린다. */
    public void blockForRisk(List<String> riskReasons) {
        requireStatus("SCHEDULED");
        this.status = "BLOCKED";
        if (riskReasons != null && !riskReasons.isEmpty()) {
            this.guardrailFlags = riskReasons.toArray(new String[0]);
        }
    }

    /** 예약 이후 필수 참조나 매장 활성 조건이 사라지면 fail-closed로 종결한다. */
    public void blockForScheduling(String reason) {
        requireStatus("SCHEDULED");
        this.status = "BLOCKED";
        this.guardrailFlags = new String[] {reason};
    }

    /** 실 모델이 아닌 결과는 무인 게시할 수 없으므로 운영 화면에서 식별 가능한 BLOCKED로 남긴다. */
    public void blockForAutomationUnavailable() {
        requireStatus("DRAFT");
        this.status = "BLOCKED";
        this.guardrailFlags = new String[] {"AUTOMATION_MODEL_UNAVAILABLE"};
    }

    /**
     * 게시 결과 반영(S10). 워커가 실제 플랫폼 상태의 근거이므로 여기서는 현재 status 를 강하게 검증하지 않는다.
     * ponytail: 디스패치 유실 등으로 늦게 도착한 결과가 상태를 덮어쓸 수 있는 경합 창이 있다 — 재시도 큐가
     * 매장당 순차 처리라 실무 위험은 낮다고 보고 넘어간다. 동시성 문제가 실측되면 낙관적 락(버전 컬럼) 추가.
     */
    public void markPublished(String platformCommentId) {
        if ("PUBLISHED".equals(this.status)) {
            return;
        }
        requireStatus("SCHEDULED");
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
        this.platformCommentId = platformCommentId;
        this.failCode = null;
        this.failReason = null;
    }

    /** ALREADY_REPLIED 는 실패가 아니라 정상 종료다(CLAUDE.md 알려진 ECODE 표, 절대규칙 없음이지만 상태머신 문서에 명시). */
    public void markAlreadyReplied() {
        if ("ALREADY_REPLIED".equals(this.status)) {
            return;
        }
        requireStatus("SCHEDULED");
        this.status = "ALREADY_REPLIED";
        this.failCode = null;
        this.failReason = null;
    }

    /** FAIL 이지만 재시도 여지가 있을 때: SCHEDULED 로 되돌리고 retry_count 를 올린다. */
    public void retryLater(Instant nextScheduledAt, String failReason) {
        requireStatus("SCHEDULED");
        this.retryCount = (short) (this.retryCount + 1);
        this.status = "SCHEDULED";
        this.scheduledAt = nextScheduledAt;
        this.failReason = failReason;
    }

    /**
     * 시도조차 하지 않은 실패 — 재시도 횟수를 태우지 않고 다시 예약만 한다.
     *
     * <p>★ DataAPI 쓰기 스위치가 꺼져 있어 반려된 경우가 이것이다. 리뷰나 답글의 문제가 아니라
     * 우리 운영 상태이므로, 재시도를 소진시키면 스위치를 켜기도 전에 초안이 죽는다.
     */
    public void deferWithoutRetry(Instant nextScheduledAt, String reason) {
        requireStatus("SCHEDULED");
        this.status = "SCHEDULED";
        this.scheduledAt = nextScheduledAt;
        this.failReason = reason;
    }

    /** 재시도 소진(FAIL) 또는 연동끊김(LINK_ERROR) — 최종 실패로 확정한다. */
    public void markFailed(String failCode, String failReason) {
        if ("FAILED".equals(this.status)) {
            return;
        }
        requireStatus("SCHEDULED");
        this.status = "FAILED";
        this.failCode = failCode;
        this.failReason = failReason;
    }

    /**
     * 워커가 게시 직전 절대규칙 3 이중검증으로 DataAPI 를 호출하지 않고 거절한 경우(action=FAIL,
     * failReason=RISK_LEVEL_TOO_HIGH, 오케스트레이터 계약 보완). 재시도 대상이 아니라 사람 검수(BLOCKED)로 종결한다 —
     * retry_count 를 올리지 않는다.
     */
    public void blockAtPublishGuard(String failCode, String reason) {
        requireStatus("SCHEDULED");
        this.status = "BLOCKED";
        this.failCode = failCode;
        this.failReason = reason;
        this.guardrailFlags = new String[] {reason};
    }

    private void requireStatus(String... allowed) {
        for (String s : allowed) {
            if (s.equals(this.status)) {
                return;
            }
        }
        throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                Map.of("draftId", this.id, "currentStatus", this.status, "allowed", List.of(allowed)));
    }
}
