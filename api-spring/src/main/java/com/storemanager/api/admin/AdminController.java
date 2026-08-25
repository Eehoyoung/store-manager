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
    private final AdminSubscriptionService subscriptions;
    private final AdminFailureService failures;

    public AdminController(AdminAccessGuard guard, FranchiseService franchises,
            AdminSubscriptionService subscriptions, AdminFailureService failures) {
        this.guard = guard;
        this.franchises = franchises;
        this.subscriptions = subscriptions;
        this.failures = failures;
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

    /** 매장별 서비스 상태. 운영자가 무엇을 결정해야 하는지 한 화면에서 본다. */
    @GetMapping("/stores")
    public List<AdminSubscriptionService.StoreServiceRow> stores() {
        guard.requireAdmin(CurrentUser.publicId());
        return subscriptions.list();
    }

    /**
     * 입금 확인 후 구독 활성화.
     * ★ 이 호출부터 그 매장에 DataAPI 호출과 LLM 토큰이 나가기 시작한다. 근거(note)를 함께 남긴다.
     */
    @PostMapping("/stores/{storeId}/subscription/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID storeId, @Valid @RequestBody ServiceDecisionRequest req) {
        var admin = guard.requireAdmin(CurrentUser.publicId());
        subscriptions.activate(storeId, req.note(), admin.getId());
    }

    /** 서비스 정지. 해지가 아니라 정지다 — 재개할 수 있다. */
    @PostMapping("/stores/{storeId}/subscription/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID storeId, @Valid @RequestBody ServiceDecisionRequest req) {
        var admin = guard.requireAdmin(CurrentUser.publicId());
        subscriptions.suspend(storeId, req.note(), admin.getId());
    }

    public record AdminMe(boolean admin) {}
    /** note 는 입금자명·입금일 같은 판단 근거다. 요금 분쟁 시 유일한 기록이므로 필수로 받는다. */
    public record ServiceDecisionRequest(@NotBlank @jakarta.validation.constraints.Size(max = 200) String note) {}
    public record DecisionRequest(@NotBlank String decision) {}

    /**
     * 재시도를 소진하고 실패한 건 목록.
     *
     * <p>★ DataAPI 재시도는 2회까지다. 그 이상은 재시도하지 않고 여기 쌓인다 —
     * 이 화면을 아무도 안 보면 실패한 답글은 그대로 사라진다.
     * <p>★ 조회 전용이다. 재시도 버튼을 붙이지 말 것(댓글 등록은 되돌릴 수 없다).
     */
    @GetMapping("/failures")
    public FailureReport failures(@RequestParam(defaultValue = "100") int limit) {
        guard.requireAdmin(CurrentUser.publicId());
        return new FailureReport(
                failures.publishFailures(limit),
                failures.collectFailures(limit));
    }

    public record FailureReport(
            List<AdminFailureService.PublishFailureRow> publishFailures,
            List<AdminFailureService.CollectFailureRow> collectFailures) {
    }
}
