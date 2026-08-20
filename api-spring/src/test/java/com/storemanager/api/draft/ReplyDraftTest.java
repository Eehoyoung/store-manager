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
    void DRAFT는_승인하면_SCHEDULED가_되고_승인자와_예약시각이_남는다() {
        ReplyDraft d = draft("DRAFT");
        Instant scheduledAt = Instant.parse("2026-08-19T02:00:00Z");

        d.approve(9L, scheduledAt);

        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.getApprovedBy()).isEqualTo(9L);
        assertThat(d.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @Test
    void 자동승인은_approvedBy가_null이다() {
        ReplyDraft d = draft("DRAFT");
        d.approve(null, Instant.now());
        assertThat(d.getApprovedBy()).isNull();
        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    void DRAFT가_아니면_승인할_수_없다() {
        ReplyDraft d = draft("REJECTED");
        ApiException ex = assertThrows(ApiException.class, () -> d.approve(9L, Instant.now()));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_DRAFT_STATE);
    }

    @Test
    void DRAFT는_거절하면_REJECTED가_된다() {
        ReplyDraft d = draft("DRAFT");
        d.reject();
        assertThat(d.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void SCHEDULED는_거절할_수_없다() {
        ReplyDraft d = draft("SCHEDULED");
        assertThrows(ApiException.class, d::reject);
    }

    @Test
    void BLOCKED_초안을_직접_수정하면_DRAFT로_돌아가고_가드레일_플래그가_비워진다() {
        ReplyDraft d = draft("BLOCKED");
        d = ReplyDraft.builder().id(1L).reviewId(10L).storeId(100L).content("")
                .status("BLOCKED").generatedBy("AI").guardrailFlags(new String[] {"G3_COMPENSATION"}).build();

        d.editContent("사장님이 직접 쓴 답글입니다");

        assertThat(d.getStatus()).isEqualTo("DRAFT");
        assertThat(d.getContent()).isEqualTo("사장님이 직접 쓴 답글입니다");
        assertThat(d.getGeneratedBy()).isEqualTo("AI_EDITED");
        assertThat(d.getGuardrailFlags()).isEmpty();
    }

    @Test
    void PUBLISHED_초안은_내용을_수정할_수_없다() {
        ReplyDraft d = draft("PUBLISHED");
        assertThrows(ApiException.class, () -> d.editContent("수정시도"));
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
        d.blockAtPublishRisk("RISK_LEVEL_TOO_HIGH");
        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.getRetryCount()).isEqualTo((short) 0);
        assertThat(d.getFailReason()).isEqualTo("RISK_LEVEL_TOO_HIGH");
    }
}
