package com.storemanager.api.agreement;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.hq.HqReviewAccessProperties;
import com.storemanager.api.security.CurrentUser;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/agreements")
public class AgreementController {
    private static final String HQ_WITHDRAWAL_ACTION = "HQ_AFFILIATION_WITHDRAWAL_REQUESTED";

    private final AgreementService service;
    private final AppUserRepository userRepository;
    private final AuditLogRepository auditRepository;
    private final HqReviewAccessProperties reviewAccessProperties;

    public AgreementController(AgreementService service, AppUserRepository userRepository,
            AuditLogRepository auditRepository, HqReviewAccessProperties reviewAccessProperties) {
        this.service = service;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.reviewAccessProperties = reviewAccessProperties;
    }

    @GetMapping
    public AgreementCatalog catalog() {
        List<AgreementItem> items = new ArrayList<>(List.of(
                new AgreementItem(AgreementService.TERMS, "이용약관", true,
                        "(필수) 이용약관에 동의합니다", "/api/v1/agreements/documents/terms"),
                new AgreementItem(AgreementService.PRIVACY, "개인정보 수집·이용", true,
                        "(필수) 개인정보 수집·이용에 동의합니다", "/api/v1/agreements/documents/privacy"),
                new AgreementItem(AgreementService.HQ, "가맹본부 제3자 제공", false,
                        "(선택) 가맹본부에 위 정보가 제공되는 것에 동의합니다",
                        "/api/v1/agreements/documents/hq-data-sharing"),
                new AgreementItem(AgreementService.CREDENTIAL, "배달앱 로그인 정보 처리 위탁", true,
                        "(필수) 배달앱 로그인 정보의 처리 위탁에 동의합니다",
                        "/api/v1/agreements/documents/platform-credential")));
        // ★ 플래그가 꺼져 있으면 카탈로그에서도 숨긴다 — 기능이 없는데 동의 항목만 보이면 혼란스럽다.
        if (reviewAccessProperties.isEnabled()) {
            items.add(new AgreementItem(AgreementService.HQ_REVIEW_SHARING, "가맹본부 개별 리뷰 제공", false,
                    "(선택) 가맹본부에 개별 리뷰 원문이 제공되는 것에 동의합니다",
                    "/api/v1/agreements/documents/hq-review-sharing"));
        }
        return new AgreementCatalog(AgreementService.CURRENT_VERSION, items);
    }

    @GetMapping("/documents/terms")
    public AgreementDocument terms() { return document("terms"); }

    @GetMapping("/documents/privacy")
    public AgreementDocument privacy() { return document("privacy"); }

    @GetMapping("/documents/hq-data-sharing")
    public AgreementDocument hq() { return document("hq-data-sharing"); }

    @GetMapping("/documents/platform-credential")
    public AgreementDocument credential() { return document("platform-credential"); }

    @GetMapping("/documents/hq-review-sharing")
    public AgreementDocument hqReviewSharing() {
        if (!reviewAccessProperties.isEnabled()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return document("hq-review-sharing");
    }

    @GetMapping("/history")
    public List<AgreementService.AgreementHistoryRow> history() {
        var user = userRepository.findByPublicIdAndDeletedAtIsNull(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return service.history(user.getId());
    }

    @PostMapping("/hq-withdrawal")
    @Transactional
    public void requestHqWithdrawal() {
        var user = userRepository.findActiveByPublicIdForUpdate(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.getFranchiseBrandName() == null || user.getFranchiseBrandName().isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (auditRepository.existsByActionAndActorId(HQ_WITHDRAWAL_ACTION, user.getId())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }
        service.record(user.getId(), null, AgreementService.HQ, false, null, null);
        auditRepository.save(AuditLog.builder().actorId(user.getId()).actorType("USER")
                .action(HQ_WITHDRAWAL_ACTION).targetType("APP_USER")
                .targetId(user.getId()).build());
        // 실제 소속 해제는 운영자가 관리자 화면의 접수 내역을 확인해 처리한다.
    }

    @GetMapping("/hq-withdrawal/status")
    public HqWithdrawalStatus hqWithdrawalStatus() {
        AppUser user = userRepository.findByPublicIdAndDeletedAtIsNull(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        boolean affiliated = user.getFranchiseBrandName() != null && !user.getFranchiseBrandName().isBlank();
        boolean requested = affiliated && auditRepository.existsByActionAndActorId(HQ_WITHDRAWAL_ACTION, user.getId());
        boolean reviewSharingAgreed = reviewAccessProperties.isEnabled()
                && service.isCurrentlyAgreed(user.getId(), AgreementService.HQ_REVIEW_SHARING);
        return new HqWithdrawalStatus(user.getFranchiseBrandName(), affiliated && !requested, requested,
                reviewAccessProperties.isEnabled(), reviewSharingAgreed);
    }

    /** docs/26a endpoints.ownerConsent — 개별 리뷰 제공 동의/철회. 플래그가 꺼져 있으면 404. */
    @PostMapping("/hq-review-sharing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void setHqReviewSharing(@Valid @RequestBody ReviewSharingRequest req, HttpServletRequest request) {
        if (!reviewAccessProperties.isEnabled()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AppUser user = userRepository.findByPublicIdAndDeletedAtIsNull(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.getFranchiseBrandName() == null || user.getFranchiseBrandName().isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        service.record(user.getId(), null, AgreementService.HQ_REVIEW_SHARING, req.agreed(), clientIp(request),
                request.getHeader("User-Agent"));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        try {
            java.net.InetAddress.getByName(value);
            return value;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    public record HqWithdrawalStatus(String brandName, boolean canRequest, boolean requested,
            boolean reviewSharingEnabled, boolean reviewSharingAgreed) {}

    public record ReviewSharingRequest(boolean agreed) {}

    private AgreementDocument document(String name) {
        try (var in = new ClassPathResource("agreements/" + AgreementService.CURRENT_VERSION + "/" + name + ".md")
                .getInputStream()) {
            return new AgreementDocument(AgreementService.CURRENT_VERSION,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    public record AgreementCatalog(String currentVersion, List<AgreementItem> items) {}
    public record AgreementItem(String code, String name, boolean required, String summary, String documentUrl) {}
    public record AgreementDocument(String version, String content) {}
}
