package com.storemanager.api.admin;

import com.storemanager.api.billing.BrandPricingService;
import com.storemanager.api.billing.PricingDtos.AdminBrandPricingRow;
import com.storemanager.api.franchise.FranchiseDtos.AddMemberRequest;
import com.storemanager.api.franchise.FranchiseDtos.AddMemberResponse;
import com.storemanager.api.franchise.FranchiseDtos.AffiliationItem;
import com.storemanager.api.franchise.FranchiseDtos.AuditLogItem;
import com.storemanager.api.franchise.FranchiseDtos.CreateFranchiseRequest;
import com.storemanager.api.franchise.FranchiseDtos.CreateFranchiseResponse;
import com.storemanager.api.franchise.FranchiseDtos.FranchiseDetailResponse;
import com.storemanager.api.franchise.FranchiseDtos.FranchiseListItem;
import com.storemanager.api.franchise.FranchiseDtos.JoinCodeResponse;
import com.storemanager.api.franchise.FranchiseDtos.ReasonRequest;
import com.storemanager.api.franchise.FranchiseDtos.StatusRequest;
import com.storemanager.api.franchise.FranchiseDtos.UpdateMemberRequest;
import com.storemanager.api.franchise.FranchiseService;
import com.storemanager.api.security.CurrentAdmin;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 시스템 콘솔의 가맹본부 관리 (docs/26a endpoints.adminConsole). {@code /api/v1/admin/**} 는
 * {@code SecurityConfig} 가 {@code SESSION_ADMIN} 권한으로 이미 막는다.
 *
 * <p>DELETE 는 물리 삭제가 아니라 담당자 REVOKED 전이다 — 도메인 로직은 전부
 * {@link FranchiseService} 에 있고 여기는 요청·응답 변환과 사유(reason) 전달만 한다.
 */
@RestController
@RequestMapping("/api/v1/admin/franchises")
public class AdminFranchiseController {

    private final FranchiseService franchiseService;
    private final BrandPricingService brandPricingService;

    public AdminFranchiseController(FranchiseService franchiseService, BrandPricingService brandPricingService) {
        this.franchiseService = franchiseService;
        this.brandPricingService = brandPricingService;
    }

    @GetMapping
    public List<FranchiseListItem> list(@RequestParam(required = false) String q) {
        return franchiseService.listFranchises(q);
    }

    /** 가맹 브랜드 구간 단가(V49) — 전체 브랜드의 지난달·이번달·다음달 단가. */
    @GetMapping("/pricing")
    public List<AdminBrandPricingRow> pricing() {
        return brandPricingService.summaryAll(java.time.Instant.now());
    }

    /** 계약상 약정 매장 수 설정. null 이면 약정 해제(기본 단가로 돌아간다). */
    @PatchMapping("/{brand}/committed-store-count")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCommittedStoreCount(@PathVariable String brand,
            @Valid @RequestBody CommittedStoreCountRequest req) {
        franchiseService.setCommittedStoreCount(brand, req.committedStoreCount(), req.reason(), CurrentAdmin.ref());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateFranchiseResponse create(@Valid @RequestBody CreateFranchiseRequest req) {
        return franchiseService.createFranchise(req, CurrentAdmin.ref());
    }

    @GetMapping("/{brand}")
    public FranchiseDetailResponse detail(@PathVariable String brand) {
        return franchiseService.getFranchise(brand);
    }

    @PatchMapping("/{brand}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStatus(@PathVariable String brand, @Valid @RequestBody StatusRequest req) {
        franchiseService.setStatus(brand, req.status(), req.reason(), CurrentAdmin.ref());
    }

    @PostMapping("/{brand}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public AddMemberResponse addMember(@PathVariable String brand, @Valid @RequestBody AddMemberRequest req) {
        return franchiseService.addMember(brand, req, CurrentAdmin.ref());
    }

    @PatchMapping("/{brand}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMember(@PathVariable String brand, @PathVariable UUID memberId,
            @Valid @RequestBody UpdateMemberRequest req) {
        franchiseService.updateMember(brand, memberId, req, CurrentAdmin.ref());
    }

    /** 물리 삭제가 아니다 — REVOKED 전이(FranchiseService.revokeMember). */
    @DeleteMapping("/{brand}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMember(@PathVariable String brand, @PathVariable UUID memberId,
            @Valid @RequestBody ReasonRequest req) {
        franchiseService.revokeMember(brand, memberId, req.reason(), CurrentAdmin.ref());
    }

    @PostMapping("/{brand}/members/{memberId}/sessions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSessions(@PathVariable String brand, @PathVariable UUID memberId,
            @Valid @RequestBody ReasonRequest req) {
        franchiseService.revokeMemberSessions(brand, memberId, req.reason(), CurrentAdmin.ref());
    }

    @PostMapping("/{brand}/join-code/rotate")
    public JoinCodeResponse rotateJoinCode(@PathVariable String brand, @Valid @RequestBody ReasonRequest req) {
        return new JoinCodeResponse(franchiseService.rotateJoinCode(brand, req.reason(), CurrentAdmin.ref()));
    }

    @PatchMapping("/{brand}/join-code/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setJoinCodeStatus(@PathVariable String brand, @Valid @RequestBody JoinCodeStatusRequest req) {
        franchiseService.setJoinCodeStatus(brand, req.active(), req.reason(), CurrentAdmin.ref());
    }

    @GetMapping("/{brand}/audit-logs")
    public List<AuditLogItem> auditLogs(@PathVariable String brand, @RequestParam(defaultValue = "100") int limit) {
        return franchiseService.listAuditLogs(brand, limit);
    }

    public record JoinCodeStatusRequest(boolean active, @jakarta.validation.constraints.NotBlank String reason) {
    }

    /** committedStoreCount 가 null 이면 약정 해제. reason 은 선택(감사로그 참고용). */
    public record CommittedStoreCountRequest(Integer committedStoreCount, String reason) {
    }
}
