package com.storemanager.api.platform;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
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

/** 배달앱 계정 등록과 소유 매장 지정. DataAPI 매장 조회는 공식 규격 수령 전까지 보류한다. */
@Service
public class PlatformAccountService {

    private static final String VERIFY_DEFERRED = "DATAAPI_VERIFY_DEFERRED";
    private static final String VERIFY_MESSAGE = "DataAPI 토큰과 LOGINPWD 공식 암호화 규격 확인 후 검증을 재개합니다.";

    private final AppUserRepository appUserRepository;
    private final StoreRepository storeRepository;
    private final PlatformAccountRepository accountRepository;
    private final StorePlatformLinkRepository linkRepository;
    private final CredentialService credentialService;

    public PlatformAccountService(AppUserRepository appUserRepository, StoreRepository storeRepository,
            PlatformAccountRepository accountRepository, StorePlatformLinkRepository linkRepository,
            CredentialService credentialService) {
        this.appUserRepository = appUserRepository;
        this.storeRepository = storeRepository;
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.credentialService = credentialService;
    }

    @Transactional
    public PlatformAccountResponse register(UUID ownerPublicId, RegisterPlatformAccountRequest request) {
        AppUser owner = appUserRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String platform = normalizePlatform(request.platform());
        String loginId = request.loginId().trim();
        if (accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull(platform, loginId)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }
        storeRepository.findByPublicIdAndDeletedAtIsNull(request.storeId())
                .filter(candidate -> candidate.getOwnerId().equals(owner.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        PlatformAccount account = credentialService.save(owner.getId(), platform, loginId, request.password());
        account.markVerificationPending(VERIFY_DEFERRED);
        // TODO(DataAPI): 공식 토큰·LOGINPWD 규격 수령 후 Worker가 등록 1건당 reviewManagement를 1회 호출한다.
        // Spring에서 외부 API를 직접 호출하거나 STOREID를 추정해 매핑하지 않는다.
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
    public void revoke(UUID ownerPublicId, UUID accountPublicId) {
        AppUser owner = appUserRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        PlatformAccount account = accountRepository
                .findByPublicIdAndOwnerIdAndRevokedAtIsNull(accountPublicId, owner.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        linkRepository.deleteByAccountId(account.getId());
        credentialService.revoke(account);
    }

    private PlatformAccountResponse toResponse(PlatformAccount account) {
        List<PlatformStoreLinkResponse> links = linkRepository.findByAccountIdOrderByCreatedAtAsc(account.getId()).stream()
                .map(link -> storeRepository.findById(link.getStoreId())
                        .map(store -> new PlatformStoreLinkResponse(store.getPublicId().toString(),
                                link.getPlatformStoreId(), link.getStoreNameSnapshot()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        String message = "ERROR".equals(account.getLinkStatus())
                ? "DataAPI 오류가 기록되었습니다. 공식 검증 규격 확인 후 재연동이 필요합니다."
                : VERIFY_MESSAGE;
        return new PlatformAccountResponse(account.getPublicId().toString(), account.getPlatform(),
                maskLoginId(account.getLoginId()), account.getLinkStatus(), "DATAAPI_VERIFY_DEFERRED", message,
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
