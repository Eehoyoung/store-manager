package com.storemanager.api.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 네이버 승인 큐 상태머신 단위테스트(docs/naver/04-extension-spec.md 승인 큐 상태머신).
 * ★ DRAFTED→APPROVED 직접 전이 거부가 이 파일에서 가장 중요한 케이스다 — "사람이 실제로
 * 봤는가"(VIEWED)를 우회하면 일괄승인의 법적 방어 논리(Tier 1 성립조건)가 무너진다.
 */
class NaverReviewEventTest {

    private NaverReviewEvent drafted() {
        return NaverReviewEvent.builder().id(1L).storeId(100L).reviewHash("h".repeat(64))
                .status("DRAFTED").build();
    }

    @Test
    void DRAFTED에서_APPROVED로_직접_전이하면_거부된다() {
        NaverReviewEvent event = drafted();

        ApiException ex = assertThrows(ApiException.class, event::markApproved);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_DRAFT_STATE);
        assertThat(event.getStatus()).isEqualTo("DRAFTED");
    }

    @Test
    void VIEWED를_거친_뒤에는_APPROVED로_전이된다() {
        NaverReviewEvent event = drafted();

        event.markViewed();
        event.markApproved();

        assertThat(event.getStatus()).isEqualTo("APPROVED");
        assertThat(event.getApprovedAt()).isNotNull();
    }

    @Test
    void EDITED를_거친_뒤_INSERTED_POSTED로_이어진다() {
        NaverReviewEvent event = drafted();

        event.markViewed();
        event.markEdited(12);
        event.markInserted();
        event.markPosted();

        assertThat(event.getStatus()).isEqualTo("POSTED");
        assertThat(event.isEdited()).isTrue();
        assertThat(event.getEditDistance()).isEqualTo(12);
        assertThat(event.getPostedAt()).isNotNull();
    }

    @Test
    void POSTED_이전이면_어디서든_SKIPPED로_전이된다() {
        NaverReviewEvent event = drafted();
        event.markViewed();

        event.markSkipped();

        assertThat(event.getStatus()).isEqualTo("SKIPPED");
    }

    @Test
    void POSTED된_항목은_다시_상태를_바꿀_수_없다() {
        NaverReviewEvent event = drafted();
        event.markViewed();
        event.markApproved();
        event.markInserted();
        event.markPosted();

        assertThrows(ApiException.class, event::markSkipped);
    }

    @Test
    void 이미_확정된_APPROVED_항목은_초안을_다시_덮어쓸_수_없다() {
        NaverReviewEvent event = drafted();
        event.markViewed();
        event.markApproved();

        assertThrows(ApiException.class,
                () -> event.refreshDraft((short) 5, "PRAISE", (short) 0, "새 초안", new String[0], false,
                        "naver-v0.1", "claude-haiku-4-5"));
    }
}
