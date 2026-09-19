package com.storemanager.api.naver;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * naver_review_event 테이블 매핑 (IMPLEMENTATION_PLAN_NAVER.md §4).
 *
 * <p>★ 이 엔티티에는 리뷰 원문 컬럼이 없다. 확장이 브라우저 로컬에서 1차 마스킹한 뒤
 * {@code review_hash}(SHA-256)만 넘기고, 서버는 그 해시와 우리가 생성한 답글({@code draftContent})만
 * 저장한다(docs/naver/03-compliance.md "리뷰 원문 서버 저장" 금지).
 *
 * <p>상태 전이(docs/naver/04-extension-spec.md 승인 큐 상태머신)를 이 클래스 하나에 모은다 —
 * {@code ReplyDraft} 와 같은 이유로, 컨트롤러·서비스가 status 를 직접 대입하지 않는다.
 *
 * <pre>
 * DRAFTED → VIEWED → (EDITED | APPROVED) → INSERTED → POSTED
 *   └──────────────────────────────────────────────→ SKIPPED (POSTED 전 어디서든)
 * </pre>
 *
 * <p>★ {@code DRAFTED → APPROVED} 직접 전이는 허용하지 않는다 — 반드시 {@code VIEWED} 를 거친다.
 * 사람이 실제로 봤다는 증거(뷰포트 진입)가 일괄승인을 Tier 0 의 연장선으로 방어하는 핵심 조건이다.
 */
@Entity
@Table(name = "naver_review_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NaverReviewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "review_hash", nullable = false, length = 64)
    private String reviewHash;

    private Short rating; // SMALLINT — 1~5. review_analysis.rating 등 기존 관례와 동일하게 Short 를 쓴다.

    private String category;

    @Builder.Default
    @Column(name = "risk_level", nullable = false)
    private short riskLevel = 0;

    @Builder.Default
    @Column(nullable = false)
    private String status = "DRAFTED";

    @Column(name = "draft_content")
    private String draftContent;

    /**
     * 이 초안을 만든 프롬프트 버전. 네이버는 배달(v2.x)과 <b>다른 라인</b>(naver-v0.x)을 쓴다 — 배달
     * 프롬프트는 골든셋 564건으로 검증됐지만 네이버 라인은 아직 평가셋이 없으므로 같은 번호를 쓰면
     * 벌지 않은 신뢰를 빌리는 셈이 된다. 나중에 품질을 되짚으려면 이 값이 남아 있어야 한다.
     */
    @Column(name = "prompt_version")
    private String promptVersion;

    /** 초안 생성에 쓰인 LLM 모델 id. {@code stub} 이면 실모델 없이 만든 초안이다. */
    @Column(name = "model")
    private String model;

    @Builder.Default
    @Column(name = "guardrail_flags", nullable = false)
    private String[] guardrailFlags = new String[0];

    @Builder.Default
    @Column(nullable = false)
    private boolean blocked = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean edited = false;

    @Column(name = "edit_distance")
    private Integer editDistance;

    @Column(name = "drafted_at")
    private Instant draftedAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "inserted_at")
    private Instant insertedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** AI 재호출로 초안 내용을 갱신한다. 이미 사람이 확정(APPROVED/POSTED)한 건은 덮어쓰지 않는다. */
    public void refreshDraft(Short rating, String category, short riskLevel, String draftContent,
            String[] guardrailFlags, boolean blocked, String promptVersion, String model) {
        if ("APPROVED".equals(status) || "POSTED".equals(status)) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                    Map.of("currentStatus", status, "reason", "이미 확정된 항목은 다시 생성할 수 없습니다."));
        }
        this.rating = rating;
        this.category = category;
        this.riskLevel = riskLevel;
        this.draftContent = draftContent;
        this.guardrailFlags = guardrailFlags == null ? new String[0] : guardrailFlags;
        this.blocked = blocked;
        this.promptVersion = promptVersion;
        this.model = model;
        this.draftedAt = Instant.now();
    }

    /** DRAFTED → VIEWED. 사장님이 사이드패널에서 실제로 확인했다(뷰포트 진입). */
    public void markViewed() {
        requireStatus("DRAFTED");
        this.status = "VIEWED";
        this.viewedAt = Instant.now();
    }

    /** VIEWED → EDITED. 사장님이 초안을 고쳤다. */
    public void markEdited(Integer editDistance) {
        requireStatus("VIEWED");
        this.status = "EDITED";
        this.edited = true;
        this.editDistance = editDistance;
    }

    /** VIEWED|EDITED → APPROVED. 일괄승인은 VIEWED 만 대상으로 하므로 여기까지 오는 EDITED 는 개별 승인 경로다. */
    public void markApproved() {
        requireStatus("VIEWED", "EDITED");
        this.status = "APPROVED";
        this.approvedAt = Instant.now();
    }

    /** EDITED|APPROVED → INSERTED. 확장이 네이버 입력창에 채워 넣었다. */
    public void markInserted() {
        requireStatus("EDITED", "APPROVED");
        this.status = "INSERTED";
        this.insertedAt = Instant.now();
    }

    /** INSERTED → POSTED. 사장님이 네이버 네이티브 "등록"을 직접 눌렀다(isTrusted 이벤트만 여기로 온다). */
    public void markPosted() {
        requireStatus("INSERTED");
        this.status = "POSTED";
        this.postedAt = Instant.now();
    }

    /** 어디서든(POSTED 이전) → SKIPPED. 사장님이 건너뛰기로 했다. */
    public void markSkipped() {
        requireStatus("DRAFTED", "VIEWED", "EDITED", "APPROVED", "INSERTED");
        this.status = "SKIPPED";
    }

    private void requireStatus(String... allowed) {
        for (String s : allowed) {
            if (s.equals(this.status)) {
                return;
            }
        }
        throw new ApiException(ErrorCode.INVALID_DRAFT_STATE,
                Map.of("eventId", this.id, "currentStatus", this.status, "allowed", List.of(allowed)));
    }
}
