package com.storemanager.api.naver;

import com.storemanager.api.naver.NaverDtos.BulkApproveRequest;
import com.storemanager.api.naver.NaverDtos.BulkApproveResponse;
import com.storemanager.api.naver.NaverDtos.DraftRequest;
import com.storemanager.api.naver.NaverDtos.DraftResponse;
import com.storemanager.api.naver.NaverDtos.EventRequest;
import com.storemanager.api.naver.NaverDtos.PairRequest;
import com.storemanager.api.naver.NaverDtos.PairResponse;
import com.storemanager.api.naver.NaverDtos.PairingCodeResponse;
import com.storemanager.api.naver.NaverDtos.SelectorMissRequest;
import com.storemanager.api.naver.NaverDtos.SetPinRequest;
import com.storemanager.api.naver.NaverDtos.StatusResponse;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 네이버 스마트플레이스 확장 연동 API (IMPLEMENTATION_PLAN_NAVER.md §5).
 *
 * <p>인증은 두 갈래다 — pairing-code/pin 발급은 웹 로그인(JWT), 나머지는 확장 토큰
 * ({@code X-Extension-Token}, {@link ExtensionTokenFilter})이다. 둘 다 {@link CurrentUser} 로
 * 같은 형태의 principal 을 꺼내므로 컨트롤러 코드는 인증 수단을 구분하지 않는다.
 */
@RestController
public class NaverController {

    private static final Logger log = LoggerFactory.getLogger(NaverController.class);

    private final ExtensionAuthService extensionAuthService;
    private final NaverDraftService naverDraftService;
    private final NaverEventService naverEventService;
    private volatile String selectorSpecJson;

    public NaverController(ExtensionAuthService extensionAuthService, NaverDraftService naverDraftService,
            NaverEventService naverEventService) {
        this.extensionAuthService = extensionAuthService;
        this.naverDraftService = naverDraftService;
        this.naverEventService = naverEventService;
    }

    @PostMapping("/api/v1/naver/extension/pairing-code")
    public PairingCodeResponse issuePairingCode() {
        String code = extensionAuthService.issuePairingCode(CurrentUser.publicId());
        return new PairingCodeResponse(code, 300);
    }

    @PostMapping("/api/v1/naver/extension/pair")
    public PairResponse pair(@Valid @RequestBody PairRequest req) {
        String token = extensionAuthService.pair(req.code());
        return new PairResponse(token, java.time.Duration.ofDays(30).toSeconds());
    }

    @PostMapping("/api/v1/naver/extension/pin")
    public ResponseEntity<Void> setPin(@Valid @RequestBody SetPinRequest req) {
        extensionAuthService.setPin(CurrentUser.publicId(), req.pin());
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/api/v1/naver/selector-spec", produces = MediaType.APPLICATION_JSON_VALUE)
    public String selectorSpec() {
        String cached = selectorSpecJson;
        if (cached != null) {
            return cached;
        }
        try {
            byte[] bytes = new ClassPathResource("naver/selector-spec.json").getContentAsByteArray();
            cached = new String(bytes, StandardCharsets.UTF_8);
            selectorSpecJson = cached;
            return cached;
        } catch (IOException e) {
            log.error("셀렉터 스펙 리소스를 읽을 수 없습니다", e);
            return "{}";
        }
    }

    @PostMapping("/api/v1/naver/drafts")
    public DraftResponse createDraft(@Valid @RequestBody DraftRequest req) {
        return naverDraftService.generateDraft(CurrentUser.publicId(), req);
    }

    @PostMapping("/api/v1/naver/events")
    public ResponseEntity<Void> recordEvent(@Valid @RequestBody EventRequest req) {
        naverEventService.recordEvent(CurrentUser.publicId(), req.storeId(), req.reviewHash(), req.event(),
                req.editDistance());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/naver/events/bulk-approve")
    public BulkApproveResponse bulkApprove(@Valid @RequestBody BulkApproveRequest req) {
        return naverEventService.bulkApprove(CurrentUser.publicId(), req.storeId(), req.reviewHashes(), req.pin());
    }

    /** ★ selectorKey/pagePath/extensionVersion 만 로그에 남긴다. DOM·본문은 절대 받지 않는다. */
    @PostMapping("/api/v1/naver/telemetry/selector-miss")
    public ResponseEntity<Void> selectorMiss(@RequestBody SelectorMissRequest req) {
        log.info("네이버 셀렉터 미스 selectorKey={} pagePath={} extensionVersion={}", req.selectorKey(), req.pagePath(),
                req.extensionVersion());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/naver/status")
    public StatusResponse status(@RequestParam String storeId) {
        return naverEventService.status(CurrentUser.publicId(), storeId);
    }
}
