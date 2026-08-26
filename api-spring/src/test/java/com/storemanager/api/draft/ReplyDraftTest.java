package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ReplyDraft 상태 머신 단위테스트(S4, S12-b). 허용 전이·거부 전이를 모두 검증한다. */
class ReplyDraftTest {

    private ReplyDraft draft(String status) {
        return ReplyDraft.builder().id(1L).reviewId(10L).storeId(100L).content("초안 내용")
                .status(status).generatedBy("AI").build();
    }

    @Test
    void DRAFT는_자동예약하면_SCHEDULED가_되고_예약시각이_남는다() {
        ReplyDraft d = draft("DRAFT");
        Instant scheduledAt = Instant.parse("2026-08-19T02:00:00Z");

        d.scheduleAutomatically(scheduledAt);

        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void DRAFT가_아니면_자동예약할_수_없다() {
        ReplyDraft d = draft("BLOCKED");
        ApiException ex = assertThrows(ApiException.class, () -> d.scheduleAutomatically(Instant.now()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_DRAFT_STATE);
    }

    @Test
    void 게시스케줄러_방어검증에서_위험도가_재확인되면_SCHEDULED에서_BLOCKED로_되돌린다() {
        ReplyDraft d = draft("SCHEDULED");
        d.blockForRisk(List.of("FOOD_POISONING"));
        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.getGuardrailFlags()).containsExactly("FOOD_POISONING");
    }

    @Test
    void 게시성공_수신시_PUBLISHED로_전이하고_플랫폼댓글ID를_저장한다() {
        ReplyDraft d = draft("SCHEDULED");
        d.markPublished("comment-123");
        assertThat(d.getStatus()).isEqualTo("PUBLISHED");
        assertThat(d.getPlatformCommentId()).isEqualTo("comment-123");
        assertThat(d.getPublishedAt()).isNotNull();
    }

    @Test
    void 이미답글존재_수신시_ALREADY_REPLIED로_전이하고_실패가_아니다() {
        ReplyDraft d = draft("SCHEDULED");
        d.markAlreadyReplied();
        assertThat(d.getStatus()).isEqualTo("ALREADY_REPLIED");
        assertThat(d.getFailCode()).isNull();
    }

    @Test
    void 재시도가능한_실패는_SCHEDULED로_되돌아가고_retryCount가_증가한다() {
        ReplyDraft d = draft("SCHEDULED");
        Instant next = Instant.now().plusSeconds(120);
        d.retryLater(next, "TIMEOUT");
        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.getRetryCount()).isEqualTo((short) 1);
        assertThat(d.getScheduledAt()).isEqualTo(next);
    }

    @Test
    void 재시도소진시_FAILED로_확정된다() {
        ReplyDraft d = draft("SCHEDULED");
        d.markFailed("ERR_UNKNOWN", "3회 재시도 소진");
        assertThat(d.getStatus()).isEqualTo("FAILED");
        assertThat(d.getFailCode()).isEqualTo("ERR_UNKNOWN");
    }

    @Test
    void 게시직전_위험도재검증_거절은_재시도하지_않고_BLOCKED로_종결한다_retryCount는_그대로다() {
        // 오케스트레이터 계약 보완: 워커가 action=FAIL, failReason=RISK_LEVEL_TOO_HIGH 로 보고하는 경우
        ReplyDraft d = draft("SCHEDULED");
        d.blockAtPublishGuard("RISK_LEVEL_TOO_HIGH", "RISK_LEVEL_TOO_HIGH");
        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.getRetryCount()).isEqualTo((short) 0);
        assertThat(d.getFailReason()).isEqualTo("RISK_LEVEL_TOO_HIGH");
    }

    // ── 사람 승인 (2026-08-27) ───────────────────────────────────────────
    //
    // ★ 절대규칙 3 은 "자동 게시 금지 / 사람 검수 큐로 보낸다" 다. 사람이 사유를 읽고
    //   판단하는 것이 곧 검수이므로, 승인 경로 자체는 규칙 위반이 아니다.
    //   다만 '판단이 실제로 있었다' 는 증거가 남아야 하고, 게시 직전 재검증
    //   (PublishScheduler·워커)이 그 증거로 자동 경로와 사람 경로를 가른다.

    @Test
    void BLOCKED는_사람이_승인하면_SCHEDULED가_된다() {
        ReplyDraft d = draft("BLOCKED");
        Instant ack = Instant.parse("2026-08-27T01:00:00Z");
        Instant scheduledAt = Instant.parse("2026-08-27T02:00:00Z");

        d.approveByHuman(7L, ack, null, scheduledAt);

        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(d.getApprovedBy()).isEqualTo(7L);
        assertThat(d.getRiskAckAt()).isEqualTo(ack);
        assertThat(d.isHumanApproved()).isTrue();
        assertThat(d.getContent()).isEqualTo("초안 내용");
        assertThat(d.getOriginalContent()).isNull();
    }

    @Test
    void 사람이_고쳐서_승인하면_원문을_남기고_AI_EDITED가_된다() {
        ReplyDraft d = draft("BLOCKED");

        d.approveByHuman(7L, Instant.now(), "직접 고친 답글", Instant.now());

        assertThat(d.getContent()).isEqualTo("직접 고친 답글");
        assertThat(d.getOriginalContent()).isEqualTo("초안 내용");
        assertThat(d.getGeneratedBy()).isEqualTo("AI_EDITED");
    }

    /**
     * ★ 사유 확인 시각이 없으면 엔티티가 스스로 거부해야 한다.
     * 서비스에서도 막지만, 방어선이 하나뿐이면 그 하나가 뚫릴 때 끝난다.
     */
    @Test
    void 사유_확인_시각이_없으면_승인되지_않는다() {
        ReplyDraft d = draft("BLOCKED");

        ApiException e = assertThrows(ApiException.class,
                () -> d.approveByHuman(7L, null, null, Instant.now()));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void 승인자가_없으면_승인되지_않는다() {
        ReplyDraft d = draft("BLOCKED");

        assertThrows(ApiException.class, () -> d.approveByHuman(null, Instant.now(), null, Instant.now()));

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void BLOCKED가_아닌_상태는_승인할_수_없다() {
        for (String status : List.of("DRAFT", "SCHEDULED", "PUBLISHED", "FAILED", "ALREADY_REPLIED")) {
            ReplyDraft d = draft(status);
            assertThrows(ApiException.class, () -> d.approveByHuman(7L, Instant.now(), null, Instant.now()),
                    status + " 상태에서 승인이 허용되면 안 된다");
        }
    }

    /** 자동 예약된 초안은 사람 승인 표시가 없어야 한다 — 워커가 이걸로 두 경로를 가른다. */
    @Test
    void 자동_예약은_사람_승인으로_보이지_않는다() {
        ReplyDraft d = draft("DRAFT");

        d.scheduleAutomatically(Instant.now());

        assertThat(d.isHumanApproved()).isFalse();
        assertThat(d.getApprovedBy()).isNull();
        assertThat(d.getRiskAckAt()).isNull();
    }

    @Test
    void 거절하면_BLOCKED로_남고_판단자가_기록된다() {
        ReplyDraft d = draft("BLOCKED");

        d.rejectByHuman(7L);

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.getApprovedBy()).isEqualTo(7L);
        assertThat(d.isHumanApproved()).isFalse();   // risk_ack_at 이 없으므로 게시되지 않는다
        assertThat(d.getGuardrailFlags()).contains("HUMAN_REJECTED");
    }
}
