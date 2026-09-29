package com.storemanager.api.admin;

import com.storemanager.api.franchise.FranchiseService;
import com.storemanager.api.security.CurrentAdmin;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.user.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 시스템 관리자 콘솔 (docs/26a endpoints.adminConsole 의 기존 유지분).
 * ★ /api/v1/admin/** 전체는 {@code SecurityConfig} 가 {@code SESSION_ADMIN} 권한으로 이미
 * 막는다 — 이 컨트롤러의 각 메서드는 더 이상 {@code guard.requireAdmin(...)} 을 호출하지 않는다.
 * 관리자는 app_user 가 없어 {@link CurrentAdmin} 으로 이메일·비식별 참조값만 꺼낸다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final FranchiseService franchises;
    private final AdminSubscriptionService subscriptions;
    private final AdminFailureService failures;
    private final AuditLogRepository audits;
    private final AppUserRepository users;

    public AdminController(FranchiseService franchises, AdminSubscriptionService subscriptions,
            AdminFailureService failures, AuditLogRepository audits, AppUserRepository users) {
        this.franchises = franchises;
        this.subscriptions = subscriptions;
        this.failures = failures;
        this.audits = audits;
        this.users = users;
    }

    @GetMapping("/me")
    public AdminMe me() {
        return new AdminMe(true, CurrentAdmin.email());
    }

    @GetMapping("/franchise-requests")
    public List<FranchiseService.AffiliationResponse> requests() {
        return franchises.pendingAffiliations();
    }

    @PatchMapping("/franchise-requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable UUID requestId, @Valid @RequestBody DecisionRequest req) {
        franchises.decideAffiliation(requestId, req.decision(), req.reason(), CurrentAdmin.ref());
    }

    /** 매장별 서비스 상태. 운영자가 무엇을 결정해야 하는지 한 화면에서 본다. */
    @GetMapping("/stores")
    public List<AdminSubscriptionService.StoreServiceRow> stores() {
        return subscriptions.list();
    }

    /**
     * 입금 확인 후 구독 활성화.
     * ★ 이 호출부터 그 매장에 DataAPI 호출과 LLM 토큰이 나가기 시작한다. 근거(note)를 함께 남긴다.
     */
    @PostMapping("/stores/{storeId}/subscription/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID storeId, @Valid @RequestBody ServiceDecisionRequest req) {
        subscriptions.activate(storeId, req.note(), CurrentAdmin.ref());
    }

    /** 서비스 정지. 해지가 아니라 정지다 — 재개할 수 있다. */
    @PostMapping("/stores/{storeId}/subscription/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID storeId, @Valid @RequestBody ServiceDecisionRequest req) {
        subscriptions.suspend(storeId, req.note(), CurrentAdmin.ref());
    }

    public record AdminMe(boolean admin, String email) {}
    /** note 는 입금자명·입금일 같은 판단 근거다. 요금 분쟁 시 유일한 기록이므로 필수로 받는다. */
    public record ServiceDecisionRequest(@NotBlank @jakarta.validation.constraints.Size(max = 200) String note) {}
    public record DecisionRequest(@NotBlank String decision, String reason) {}

    @GetMapping("/hq-withdrawal-requests")
    public List<HqWithdrawalRequest> hqWithdrawalRequests() {
        return audits.findByActionOrderByCreatedAtAsc("HQ_AFFILIATION_WITHDRAWAL_REQUESTED").stream()
                .map(log -> users.findById(log.getActorId())
                        .map(user -> new HqWithdrawalRequest(log.getId(), user.getName(), user.getEmail(),
                                log.getCreatedAt())).orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }

    public record HqWithdrawalRequest(Long id, String requesterName, String requesterEmail,
            java.time.Instant requestedAt) {}

    /**
     * 재시도를 소진하고 실패한 건 목록.
     *
     * <p>★ DataAPI 재시도는 2회까지다. 그 이상은 재시도하지 않고 여기 쌓인다 —
     * 이 화면을 아무도 안 보면 실패한 답글은 그대로 사라진다.
     * <p>★ 조회 전용이다. 재시도 버튼을 붙이지 말 것(댓글 등록은 되돌릴 수 없다).
     */
    @GetMapping("/failures")
    public FailureReport failures(@RequestParam(defaultValue = "100") int limit) {
        return new FailureReport(
                failures.publishFailures(limit),
                failures.collectFailures(limit),
                failures.alimtalkFailures(limit));
    }

    public record FailureReport(
            List<AdminFailureService.PublishFailureRow> publishFailures,
            List<AdminFailureService.CollectFailureRow> collectFailures,
            List<AdminFailureService.AlimtalkFailureRow> alimtalkFailures) {
    }
}
