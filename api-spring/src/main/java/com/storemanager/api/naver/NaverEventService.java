package com.storemanager.api.naver;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.naver.NaverDtos.BulkApproveResponse;
import com.storemanager.api.naver.NaverDtos.StatusResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 네이버 승인 큐 상태머신 서버 강제 + 일괄승인 (IMPLEMENTATION_PLAN_NAVER.md §2.3, docs/naver/03 §Tier 1).
 *
 * <p>★ 상태 전이 자체의 유효성은 {@link NaverReviewEvent} 가 스스로 검사한다(requireStatus).
 * 이 서비스는 그 위에 "누가 이 매장을 만질 수 있는가" 와 "일괄승인 3대 제외조건" 만 얹는다.
 */
@Service
public class NaverEventService {

    /** docs/naver/03-compliance.md "Tier 1 성립 조건" 3번 — 1~2점 리뷰는 일괄승인 대상에서 무조건 제외. */
    private static final int BULK_MIN_RATING = 3;
    /** 위험도가 애매하거나 높으면 사람이 개별로 봐야 한다. DraftService 의 risk>=2 차단과 같은 기준선. */
    private static final short BULK_MAX_RISK = 2;

    private static final List<String> STATUSES =
            List.of("DRAFTED", "VIEWED", "EDITED", "APPROVED", "INSERTED", "POSTED", "SKIPPED");

    private final NaverReviewEventRepository naverReviewEventRepository;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    public NaverEventService(NaverReviewEventRepository naverReviewEventRepository, StoreRepository storeRepository,
            AppUserRepository appUserRepository, AuditLogRepository auditLogRepository,
            PasswordEncoder passwordEncoder) {
        this.naverReviewEventRepository = naverReviewEventRepository;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** POST /api/v1/naver/events. 단일 항목 상태 전이 기록. */
    @Transactional
    public void recordEvent(UUID ownerPublicId, String storePublicId, String reviewHash, String eventName,
            Integer editDistance) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        NaverReviewEvent event = naverReviewEventRepository.findByStoreIdAndReviewHash(store.getId(), reviewHash)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        applyTransition(event, eventName, editDistance);
        naverReviewEventRepository.save(event);
    }

    private void applyTransition(NaverReviewEvent event, String eventName, Integer editDistance) {
        String normalized = eventName == null ? "" : eventName.toUpperCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "VIEWED" -> event.markViewed();
            case "EDITED" -> event.markEdited(editDistance);
            case "APPROVED" -> event.markApproved();
            case "INSERTED" -> event.markInserted();
            case "POSTED" -> event.markPosted();
            case "SKIPPED" -> event.markSkipped();
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("event", "알 수 없는 이벤트입니다."));
        }
    }

    /**
     * POST /api/v1/naver/events/bulk-approve.
     *
     * <p>제외 조건(docs/naver/03 §Tier 1) — (a) VIEWED 를 거치지 않은 항목 (b) 1~2점 리뷰
     * (c) 가드레일 차단 또는 risk_level>=2. 제외된 항목은 이유와 함께 돌려준다.
     */
    @Transactional
    public BulkApproveResponse bulkApprove(UUID ownerPublicId, String storePublicId, List<String> reviewHashes,
            String pin) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);

        // ★ fail-closed — PIN 을 설정하지 않은 계정은 일괄승인 자체를 쓸 수 없다.
        // 공용 포스 PC 에서 직원이 대신 승인하는 경로를 막는 유일한 장치다(docs/naver/03).
        if (owner.getNaverBulkPinHash() == null || !passwordEncoder.matches(pin, owner.getNaverBulkPinHash())) {
            throw new ApiException(ErrorCode.FORBIDDEN, Map.of("pin", "PIN이 설정되지 않았거나 일치하지 않습니다."));
        }

        List<String> hashes = reviewHashes == null ? List.of() : reviewHashes;
        Map<String, NaverReviewEvent> byHash = new LinkedHashMap<>();
        for (NaverReviewEvent e : naverReviewEventRepository.findByStoreIdAndReviewHashIn(store.getId(), hashes)) {
            byHash.put(e.getReviewHash(), e);
        }

        List<String> approved = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        for (String hash : hashes) {
            NaverReviewEvent event = byHash.get(hash);
            if (event == null) {
                excluded.put(hash, "NOT_FOUND");
                continue;
            }
            if (!"VIEWED".equals(event.getStatus())) {
                excluded.put(hash, "NOT_VIEWED");
                continue;
            }
            if (event.getRating() == null || event.getRating() < BULK_MIN_RATING) {
                excluded.put(hash, "LOW_RATING");
                continue;
            }
            if (event.isBlocked() || event.getRiskLevel() >= BULK_MAX_RISK) {
                excluded.put(hash, "RISK_BLOCKED");
                continue;
            }
            event.markApproved();
            naverReviewEventRepository.save(event);
            approved.add(hash);
        }

        auditLogRepository.save(AuditLog.builder()
                .actorId(owner.getId())
                .actorType("USER")
                .action("NAVER_BULK_APPROVED")
                .targetType("STORE")
                .targetId(store.getId())
                .build());
        return new BulkApproveResponse(approved, excluded);
    }

    /** GET /api/v1/naver/status. */
    @Transactional(readOnly = true)
    public StatusResponse status(UUID ownerPublicId, String storePublicId) {
        AppUser owner = resolveUser(ownerPublicId);
        Store store = loadOwnedStore(owner, storePublicId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : STATUSES) {
            counts.put(s, naverReviewEventRepository.countByStoreIdAndStatus(store.getId(), s));
        }
        return new StatusResponse(counts);
    }

    private Store loadOwnedStore(AppUser owner, String storePublicId) {
        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(NaverDraftService.parseUuid(storePublicId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!store.getOwnerId().equals(owner.getId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return store;
    }

    private AppUser resolveUser(UUID publicId) {
        return appUserRepository.findByPublicId(publicId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
