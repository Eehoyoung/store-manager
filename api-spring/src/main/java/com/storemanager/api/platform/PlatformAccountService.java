package com.storemanager.api.platform;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.agreement.AgreementService;
import java.time.Instant;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 배달앱 계정 등록과 소유 매장 지정. 플랫폼 매장 발견은 Worker 의 첫 수집 때 이뤄진다. */
@Service
public class PlatformAccountService {

    private static final String VERIFY_DEFERRED = "DATAAPI_VERIFY_DEFERRED";
    private static final String VERIFY_MESSAGE = "첫 수집 작업이 돌 때 DataAPI 리뷰관리 조회 1회로 플랫폼 매장을 찾습니다.";

    private final AppUserRepository appUserRepository;
    private final StoreRepository storeRepository;
    private final PlatformAccountRepository accountRepository;
    private final StorePlatformLinkRepository linkRepository;
    private final CredentialService credentialService;
    private final AgreementService agreementService;

    public PlatformAccountService(AppUserRepository appUserRepository, StoreRepository storeRepository,
            PlatformAccountRepository accountRepository, StorePlatformLinkRepository linkRepository,
            CredentialService credentialService, AgreementService agreementService) {
        this.appUserRepository = appUserRepository;
        this.storeRepository = storeRepository;
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.credentialService = credentialService;
        this.agreementService = agreementService;
    }

    @Transactional
    public PlatformAccountResponse register(UUID ownerPublicId, RegisterPlatformAccountRequest request,
            String ip, String userAgent) {
        agreementService.requireCurrentVersion(request.docVersion());
        if (!request.agreedCredentialEntrust()) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED,
                    java.util.Map.of("missing", java.util.List.of(AgreementService.CREDENTIAL)));
        }
        AppUser owner = appUserRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String platform = normalizePlatform(request.platform());
        String loginId = request.loginId().trim();
        if (accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull(platform, loginId)) {
            throw new ApiException(ErrorCode.PLATFORM_ACCOUNT_ALREADY_LINKED);
        }
        // ★ 이 매장을 계정에 기억시킨다. 예전에는 검증만 하고 버려서, 첫 수집에서 플랫폼 매장을
        //   발견해도 어느 매장에 붙일지 몰라 수집한 리뷰가 전부 버려졌다.
        var store = storeRepository.findByPublicIdAndDeletedAtIsNull(request.storeId())
                .filter(candidate -> candidate.getOwnerId().equals(owner.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        PlatformAccount account = credentialService.save(owner.getId(), platform, loginId, request.password(),
                store.getId());
        account.markVerificationPending(VERIFY_DEFERRED);
        Instant now = Instant.now();
        store.activateByCredentialConsent(now);
        agreementService.record(owner.getId(), store.getId(), AgreementService.CREDENTIAL, true, ip, userAgent);
        // 매장 발견은 Worker 의 수집 작업이 reviewManagement 응답(REVIEWLIST[].STOREID)을 보고 수행한다.
        // ★ Spring 에서 외부 API 를 직접 호출하거나 STOREID 를 추정해 매핑하지 않는다 (서비스 간 경계).
        // ★ 등록 시점에 즉시 호출하지 않는 이유: 호출당 과금이라 오타로 재등록할 때마다 돈이 나간다.
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public List<PlatformAccountResponse> list(UUID ownerPublicId) {
        AppUser owner = appUserRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        return accountRepository.findByOwnerIdAndRevokedAtIsNullOrderByCreatedAtDesc(owner.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void revoke(UUID ownerPublicId, UUID accountPublicId, String ip, String userAgent) {
        AppUser owner = appUserRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        PlatformAccount account = accountRepository
                .findByPublicIdAndOwnerIdAndRevokedAtIsNull(accountPublicId, owner.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        // ★ 매장 조회를 파기보다 먼저, 그리고 던지지 않게 한다.
        //   intended_store_id 는 V22 에서 nullable 로 추가돼 그 이전 계정은 비어 있다.
        //   파기 뒤에 조회가 던지면 트랜잭션이 통째로 롤백돼 자격증명이 그대로 남는다 —
        //   AccountWithdrawalService 의 "다른 단계가 실패해도 자격증명만은 먼저 지운다" 와 같은 원칙이다.
        var store = account.getIntendedStoreId() == null ? null
                : storeRepository.findById(account.getIntendedStoreId())
                        .filter(candidate -> candidate.getOwnerId().equals(owner.getId()))
                        .orElse(null);
        linkRepository.deleteByAccountId(account.getId());
        credentialService.revoke(account);
        if (store != null) {
            store.clearCredentialConsent(Instant.now());
            agreementService.record(owner.getId(), store.getId(), AgreementService.CREDENTIAL, false, ip, userAgent);
        }
    }

    private PlatformAccountResponse toResponse(PlatformAccount account) {
        List<PlatformStoreLinkResponse> links = linkRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(link -> storeRepository.findById(link.getStoreId())
                        .map(store -> new PlatformStoreLinkResponse(store.getPublicId().toString(),
                                link.getPlatformStoreId(), link.getStoreNameSnapshot()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        // ★ 검증 상태를 상수로 내려보내면 몇 번을 성공해도 화면이 '검증 보류' 로 남는다.
        //   실제 상태에서 파생시킨다.
        String linkStatus = account.getLinkStatus();
        String verificationStatus;
        String message;
        if ("ERROR".equals(linkStatus)) {
            verificationStatus = "DATAAPI_VERIFY_FAILED";
            message = "DataAPI 로그인에 실패했습니다. 아이디·비밀번호를 확인해 다시 연동해 주세요.";
        } else if ("LINKED".equals(linkStatus)) {
            verificationStatus = "DATAAPI_VERIFIED";
            message = links.isEmpty()
                    ? "연동됐습니다. 플랫폼 매장 매핑을 기다리는 중입니다."
                    : "연동됐습니다. 리뷰를 자동으로 수집하고 있습니다.";
        } else {
            verificationStatus = "DATAAPI_VERIFY_DEFERRED";
            message = VERIFY_MESSAGE;
        }
        return new PlatformAccountResponse(account.getPublicId().toString(), account.getPlatform(),
                maskLoginId(account.getLoginId()), linkStatus, verificationStatus, message,
                account.getLastErrorCode(), account.getVerifiedAt(), links);
    }

    static String maskLoginId(String loginId) {
        if (loginId == null || loginId.length() <= 2) {
            return "••••";
        }
        if (loginId.length() <= 4) {
            return loginId.substring(0, 1) + "••••";
        }
        return loginId.substring(0, 2) + "••••" + loginId.substring(loginId.length() - 2);
    }

    private String normalizePlatform(String platform) {
        String normalized = platform == null ? "" : platform.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("BAEMIN") && !normalized.equals("YOGIYO") && !normalized.equals("COUPANGEATS")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

}
