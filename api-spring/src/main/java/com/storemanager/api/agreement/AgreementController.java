package com.storemanager.api.agreement;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.security.CurrentUser;
import com.storemanager.api.user.AppUserRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agreements")
public class AgreementController {
    private static final List<AgreementItem> ITEMS = List.of(
            new AgreementItem(AgreementService.TERMS, "이용약관", true,
                    "(필수) 이용약관에 동의합니다", "/api/v1/agreements/documents/terms"),
            new AgreementItem(AgreementService.PRIVACY, "개인정보 수집·이용", true,
                    "(필수) 개인정보 수집·이용에 동의합니다", "/api/v1/agreements/documents/privacy"),
            new AgreementItem(AgreementService.HQ, "가맹본부 제3자 제공", false,
                    "(선택) 가맹본부에 위 정보가 제공되는 것에 동의합니다",
                    "/api/v1/agreements/documents/hq-data-sharing"),
            new AgreementItem(AgreementService.CREDENTIAL, "배달앱 로그인 정보 처리 위탁", true,
                    "(필수) 배달앱 로그인 정보의 처리 위탁에 동의합니다",
                    "/api/v1/agreements/documents/platform-credential"));

    private final AgreementService service;
    private final AppUserRepository userRepository;
    private final AuditLogRepository auditRepository;

    public AgreementController(AgreementService service, AppUserRepository userRepository,
            AuditLogRepository auditRepository) {
        this.service = service;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public AgreementCatalog catalog() {
        return new AgreementCatalog(AgreementService.CURRENT_VERSION, ITEMS);
    }

    @GetMapping("/documents/terms")
    public AgreementDocument terms() { return document("terms"); }

    @GetMapping("/documents/privacy")
    public AgreementDocument privacy() { return document("privacy"); }

    @GetMapping("/documents/hq-data-sharing")
    public AgreementDocument hq() { return document("hq-data-sharing"); }

    @GetMapping("/documents/platform-credential")
    public AgreementDocument credential() { return document("platform-credential"); }

    @GetMapping("/history")
    public List<AgreementService.AgreementHistoryRow> history() {
        var user = userRepository.findByPublicIdAndDeletedAtIsNull(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return service.history(user.getId());
    }

    @PostMapping("/hq-withdrawal")
    public void requestHqWithdrawal() {
        var user = userRepository.findByPublicIdAndDeletedAtIsNull(CurrentUser.publicId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        service.record(user.getId(), null, AgreementService.HQ, false, null, null);
        auditRepository.save(AuditLog.builder().actorId(user.getId()).actorType("USER")
                .action("HQ_AFFILIATION_WITHDRAWAL_REQUESTED").targetType("APP_USER")
                .targetId(user.getId()).build());
        // 실제 소속 해제는 운영자가 관리자 화면의 접수 내역을 확인해 처리한다.
    }

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
