package com.storemanager.api.admin;

import com.storemanager.api.franchise.FranchiseService;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminAccessGuard guard;
    private final FranchiseService franchises;

    public AdminController(AdminAccessGuard guard, FranchiseService franchises) {
        this.guard = guard;
        this.franchises = franchises;
    }

    @GetMapping("/me")
    public AdminMe me() {
        guard.requireAdmin(CurrentUser.publicId());
        return new AdminMe(true);
    }

    @GetMapping("/franchise-requests")
    public List<FranchiseService.AffiliationResponse> requests() {
        guard.requireAdmin(CurrentUser.publicId());
        return franchises.pendingAffiliations();
    }

    @PatchMapping("/franchise-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable UUID requestId, @Valid @RequestBody DecisionRequest req) {
        var admin = guard.requireAdmin(CurrentUser.publicId());
        franchises.decideAffiliation(requestId, req.decision(), admin);
    }

    public record AdminMe(boolean admin) {}
    public record DecisionRequest(@NotBlank String decision) {}
}
