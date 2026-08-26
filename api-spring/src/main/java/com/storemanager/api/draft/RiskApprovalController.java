package com.storemanager.api.draft;

import com.storemanager.api.draft.DraftDtos.DraftResponse;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위험 리뷰 초안의 사람 승인 API (2026-08-27 신설).
 *
 * <p>★ 이 경로는 <b>위험 사유로만 막힌 초안</b>에만 열린다. 가드레일이 잡은 건
 * (금전 보상 약속·개인정보·금칙어) 여기로도 게시되지 않는다 — {@link RiskApprovalService} 참고.
 */
@RestController
public class RiskApprovalController {

    private final RiskApprovalService riskApprovalService;

    public RiskApprovalController(RiskApprovalService riskApprovalService) {
        this.riskApprovalService = riskApprovalService;
    }

    /**
     * 요청 본문.
     *
     * @param riskAcknowledged 차단 사유를 확인했다는 표시. 화면의 체크박스가 보낸다.
     *        <b>false 거나 없으면 400 이다.</b> 서버가 직접 검사하지 않으면 체크박스는
     *        API 를 직접 호출하는 순간 아무 의미도 없어진다.
     * @param content 사람이 고친 본문. 비우면 AI 초안을 그대로 게시한다.
     */
    record ApproveRequest(Boolean riskAcknowledged, @Size(max = 280) String content) {
    }

    @PostMapping("/api/v1/drafts/{draftId}/approve")
    public DraftResponse approve(@PathVariable UUID draftId,
            @RequestBody(required = false) ApproveRequest req) {
        boolean acked = req != null && Boolean.TRUE.equals(req.riskAcknowledged());
        String content = req == null ? null : req.content();
        return riskApprovalService.approve(CurrentUser.publicId(), draftId, acked, content);
    }

    @PostMapping("/api/v1/drafts/{draftId}/reject")
    public DraftResponse reject(@PathVariable UUID draftId) {
        return riskApprovalService.reject(CurrentUser.publicId(), draftId);
    }
}
