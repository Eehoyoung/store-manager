package com.storemanager.api.draft;

import com.storemanager.api.draft.DraftDtos.GenerateDraftsRequest;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsResponse;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

}
