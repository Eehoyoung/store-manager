package com.storemanager.api.draft;

import com.storemanager.api.draft.DraftDtos.ApproveRequest;
import com.storemanager.api.draft.DraftDtos.BulkApproveRequest;
import com.storemanager.api.draft.DraftDtos.BulkApproveResponse;
import com.storemanager.api.draft.DraftDtos.DraftListResponse;
import com.storemanager.api.draft.DraftDtos.DraftResponse;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsRequest;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsResponse;
import com.storemanager.api.draft.DraftDtos.PatchDraftRequest;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §6 답글 API. */
@RestController
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PostMapping("/api/v1/reviews/{reviewId}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public GenerateDraftsResponse generate(@PathVariable Long reviewId,
            @Valid @RequestBody(required = false) GenerateDraftsRequest req) {
        return draftService.generateDrafts(CurrentUser.publicId(), reviewId,
                req == null ? new GenerateDraftsRequest(1, null) : req);
    }

    @PatchMapping("/api/v1/drafts/{draftId}")
    public DraftResponse patch(@PathVariable Long draftId, @Valid @RequestBody PatchDraftRequest req) {
        return draftService.patch(CurrentUser.publicId(), draftId, req);
    }

    @PostMapping("/api/v1/drafts/{draftId}/approve")
    public DraftResponse approve(@PathVariable Long draftId, @RequestBody(required = false) ApproveRequest req) {
        return draftService.approve(CurrentUser.publicId(), draftId, req);
    }

    @PostMapping("/api/v1/drafts/{draftId}/reject")
    public DraftResponse reject(@PathVariable Long draftId) {
        return draftService.reject(CurrentUser.publicId(), draftId);
    }

    @PostMapping("/api/v1/drafts/bulk-approve")
    public BulkApproveResponse bulkApprove(@Valid @RequestBody BulkApproveRequest req) {
        return draftService.bulkApprove(CurrentUser.publicId(), req);
    }

    @GetMapping("/api/v1/stores/{storeId}/drafts")
    public DraftListResponse queue(@PathVariable UUID storeId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return draftService.listQueue(CurrentUser.publicId(), storeId, status, page, size);
    }

    @GetMapping("/api/v1/drafts/{draftId}")
    public DraftResponse get(@PathVariable Long draftId) {
        return draftService.getOne(CurrentUser.publicId(), draftId);
    }
}
