package com.storemanager.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storemanager.api.draft.ReplyDraft;
import org.junit.jupiter.api.Test;

/** 사장님 화면 '게시 대기'(대표 결정 D32) 판정 — 등록 게이트로 미뤄지는 예약 답글만 해당한다. */
class DraftSummaryPausedTest {

    private static ReplyDraft draft(String status, String failReason) {
        return ReplyDraft.builder().reviewId(1L).storeId(100L).content("답글").generatedBy("AI")
                .status(status).failReason(failReason).build();
    }

    @Test
    void 등록_게이트로_미뤄지는_예약_답글만_게시_대기다() {
        assertThat(ReviewService.toDraftSummary(draft("SCHEDULED", "DATAAPI_WRITE_DISABLED")).publishPaused()).isTrue();
        assertThat(ReviewService.toDraftSummary(draft("SCHEDULED", null)).publishPaused()).isFalse();
        assertThat(ReviewService.toDraftSummary(draft("FAILED", "DATAAPI_WRITE_DISABLED")).publishPaused()).isFalse();
    }
}
