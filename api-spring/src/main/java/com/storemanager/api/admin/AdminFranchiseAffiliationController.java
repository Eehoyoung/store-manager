package com.storemanager.api.admin;

import com.storemanager.api.franchise.FranchiseDtos.AffiliationItem;
import com.storemanager.api.franchise.FranchiseDtos.ReasonRequest;
import com.storemanager.api.franchise.FranchiseService;
import com.storemanager.api.security.CurrentAdmin;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 가맹점 소속 신청 전체 이력(대기·승인·거절·해제) — docs/26a endpoints.adminConsole. */
@RestController
@RequestMapping("/api/v1/admin/franchise-affiliations")
public class AdminFranchiseAffiliationController {

    private final FranchiseService franchiseService;

    public AdminFranchiseAffiliationController(FranchiseService franchiseService) {
        this.franchiseService = franchiseService;
    }

    @GetMapping
    public List<AffiliationItem> list(@RequestParam(required = false) String status) {
        return franchiseService.listAffiliations(status);
    }

    @PostMapping("/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        franchiseService.releaseAffiliation(id, req.reason(), CurrentAdmin.ref());
    }
}
