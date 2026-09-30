package com.storemanager.api.franchise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.agreement.UserAgreement;
import com.storemanager.api.agreement.UserAgreementRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseDtos.AddMemberRequest;
import com.storemanager.api.franchise.FranchiseDtos.AddMemberResponse;
import com.storemanager.api.franchise.FranchiseDtos.AffiliationItem;
import com.storemanager.api.franchise.FranchiseDtos.ApprovedStoreItem;
import com.storemanager.api.franchise.FranchiseDtos.AuditLogItem;
import com.storemanager.api.franchise.FranchiseDtos.CreateFranchiseRequest;
import com.storemanager.api.franchise.FranchiseDtos.CreateFranchiseResponse;
import com.storemanager.api.franchise.FranchiseDtos.FranchiseDetailResponse;
import com.storemanager.api.franchise.FranchiseDtos.FranchiseListItem;
import com.storemanager.api.franchise.FranchiseDtos.JoinCodeInfo;
import com.storemanager.api.franchise.FranchiseDtos.MemberItem;
import com.storemanager.api.franchise.FranchiseDtos.UpdateMemberRequest;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.sysauth.SessionService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가맹본부 도메인 서비스 — 시스템 콘솔의 본부·담당자·가맹코드·소속 관리(docs/26a endpoints.adminConsole)와
 * 가맹점 쪽 소속 신청(회원가입)을 함께 담당한다. 둘 다 franchise_join_code·franchise_affiliation_request·
 * franchise_hq_member 를 다루는 같은 도메인이라 서비스를 가르지 않는다.
 *
 * <p>★ 관리자는 app_user 가 없다(docs/26a decisions.admin) — 모든 쓰기 메서드는 {@code AppUser admin}
 * 대신 {@code adminRef}(비식별 문자열, {@code CurrentAdmin.ref()})를 받고 감사로그 actorId 는 null 로 남긴다.
 */
@Service
public class FranchiseService {

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FranchiseJoinCodeRepository joinCodeRepository;
    private final FranchiseBrandRepository franchiseBrandRepository;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final FranchiseAffiliationRequestRepository affiliationRepository;
    private final StoreRepository storeRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final MailService mailService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public FranchiseService(FranchiseJoinCodeRepository joinCodeRepository,
            FranchiseBrandRepository franchiseBrandRepository, FranchiseHqMemberRepository hqMemberRepository,
            AppUserRepository appUserRepository, AuditLogRepository auditLogRepository,
            FranchiseAffiliationRequestRepository affiliationRepository, StoreRepository storeRepository,
            UserAgreementRepository userAgreementRepository, MailService mailService, SessionService sessionService,
            ObjectMapper objectMapper) {
        this.joinCodeRepository = joinCodeRepository;
        this.franchiseBrandRepository = franchiseBrandRepository;
        this.hqMemberRepository = hqMemberRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.affiliationRepository = affiliationRepository;
        this.storeRepository = storeRepository;
        this.userAgreementRepository = userAgreementRepository;
        this.mailService = mailService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    // ── 가맹점 쪽 소속 신청 (기존, 회원가입 경로) ──────────────────────────────

    @Transactional
    public void requestAffiliation(AppUser user, Store store, String rawCode) {
        FranchiseJoinCode code = findActiveCode(rawCode);
        affiliationRepository.save(FranchiseAffiliationRequest.builder()
                .userId(user.getId()).storeId(store.getId()).joinCodeId(code.getId()).build());
    }

    @Transactional(readOnly = true)
    public List<AffiliationResponse> pendingAffiliations() {
        // ponytail: 관리자 대기열은 소량 전제. 수백 건을 넘으면 배치 조회 projection으로 교체한다.
        return affiliationRepository.findByStatusOrderByRequestedAtAsc("PENDING").stream().map(request -> {
            AppUser user = appUserRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            Store store = storeRepository.findById(request.getStoreId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            FranchiseJoinCode code = joinCodeRepository.findById(request.getJoinCodeId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            return new AffiliationResponse(request.getPublicId().toString(), code.getBrandName(), user.getName(),
                    user.getEmail(), store.getName(), store.getAddress(), request.getRequestedAt());
        }).toList();
    }

    @Transactional
    public void decideAffiliation(UUID requestId, String decision, String reason, String adminRef) {
        boolean approved;
        if ("APPROVE".equals(decision)) {
            approved = true;
        } else if ("REJECT".equals(decision)) {
            approved = false;
        } else {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }

        FranchiseAffiliationRequest request = affiliationRepository.findByPublicId(requestId)
                .filter(r -> "PENDING".equals(r.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String brand;
        if (approved) {
            AppUser user = appUserRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            Store store = storeRepository.findById(request.getStoreId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            FranchiseJoinCode code = joinCodeRepository.findById(request.getJoinCodeId())
                    .filter(FranchiseJoinCode::isActive).orElseThrow(() -> new ApiException(ErrorCode.INVALID_FRANCHISE_CODE));
            brand = code.getBrandName();
            user.assignFranchiseBrand(brand);
            store.assignBrand(brand);
        } else {
            brand = joinCodeRepository.findById(request.getJoinCodeId()).map(FranchiseJoinCode::getBrandName)
                    .orElse(null);
        }
        request.decide(approved, reason);
        auditFranchise(approved ? "FRANCHISE_AFFILIATION_APPROVED" : "FRANCHISE_AFFILIATION_REJECTED", brand,
                "AFFILIATION", request.getId(), adminRef, reason);
    }

    @Transactional
    public void releaseAffiliation(UUID requestId, String reason, String adminRef) {
        FranchiseAffiliationRequest request = affiliationRepository.findByPublicId(requestId)
                .filter(r -> "APPROVED".equals(r.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        AppUser user = appUserRepository.findById(request.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Store store = storeRepository.findById(request.getStoreId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String brand = store.getBrandName();
        user.assignFranchiseBrand(null);
        store.assignBrand(null);
        request.release(reason, Instant.now());
        auditFranchise("FRANCHISE_AFFILIATION_RELEASED", brand, "AFFILIATION", request.getId(), adminRef, reason);
    }

    @Transactional(readOnly = true)
    public List<AffiliationItem> listAffiliations(String status) {
        List<FranchiseAffiliationRequest> requests = (status == null || status.isBlank())
                ? affiliationRepository.findAllByOrderByRequestedAtDesc()
                : affiliationRepository.findAllByOrderByRequestedAtDesc().stream()
                        .filter(r -> status.equals(r.getStatus())).toList();
        return requests.stream().map(this::toAffiliationItem).toList();
    }

    private AffiliationItem toAffiliationItem(FranchiseAffiliationRequest r) {
        AppUser user = appUserRepository.findById(r.getUserId()).orElse(null);
        Store store = storeRepository.findById(r.getStoreId()).orElse(null);
        FranchiseJoinCode code = joinCodeRepository.findById(r.getJoinCodeId()).orElse(null);
        UserAgreement hqConsent = userAgreementRepository
                .findTopByUserIdAndStoreIdAndAgreementCodeOrderByCreatedAtDesc(r.getUserId(), r.getStoreId(),
                        AgreementService.HQ)
                .orElse(null);
        boolean reviewSharingAgreed = userAgreementRepository
                .findTopByUserIdAndAgreementCodeOrderByCreatedAtDesc(r.getUserId(), AgreementService.HQ_REVIEW_SHARING)
                .map(UserAgreement::isAgreed).orElse(false);
        return new AffiliationItem(r.getPublicId().toString(), code == null ? null : code.getBrandName(),
                user == null ? null : user.getName(), user == null ? null : user.getEmail(),
                store == null ? null : store.getName(), store == null ? null : store.getAddress(), r.getStatus(),
                r.getRequestedAt(), r.getDecidedAt(), r.getReason(),
                hqConsent == null ? null : hqConsent.getDocVersion(), hqConsent == null ? null : hqConsent.getAgreedAt(),
                reviewSharingAgreed);
    }

    // ── 시스템 콘솔: 본부 생성·목록·상세 ────────────────────────────────────

    @Transactional
    public CreateFranchiseResponse createFranchise(CreateFranchiseRequest req, String adminRef) {
        String brandName = req.brandName().trim();
        String email = normalize(req.memberEmail());
        if (franchiseBrandRepository.findByBrandName(brandName).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }
        franchiseBrandRepository.save(FranchiseBrand.builder().brandName(brandName).createdRef(adminRef).build());

        AppUser member = appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email)
                .orElseGet(() -> appUserRepository
                        .save(AppUser.builder().email(email).name(req.memberName().trim()).build()));
        FranchiseHqMember hqMember = hqMemberRepository.save(FranchiseHqMember.builder().userId(member.getId())
                .brandName(brandName).title(blankToNull(req.memberTitle())).invitedRef(adminRef).build());

        String joinCode = null;
        if (req.issueJoinCode()) {
            joinCode = generateCode();
            joinCodeRepository.save(FranchiseJoinCode.builder().brandName(brandName).codeHash(hash(joinCode)).build());
        }
        mailService.sendHqInvite(email, brandName);
        auditFranchise("FRANCHISE_CREATED", brandName, "FRANCHISE_BRAND", null, adminRef, req.reason());
        return new CreateFranchiseResponse(brandName, hqMember.getPublicId().toString(), joinCode);
    }

    @Transactional(readOnly = true)
    public List<FranchiseListItem> listFranchises(String q) {
        String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<FranchiseListItem> result = new ArrayList<>();
        for (FranchiseBrand brand : franchiseBrandRepository.findAllByOrderByBrandNameAsc()) {
            String brandName = brand.getBrandName();
            List<FranchiseHqMember> members = hqMemberRepository.findByBrandNameOrderByInvitedAtAsc(brandName);
            if (needle != null && !needle.isBlank() && !matchesQuery(brandName, members, needle)) {
                continue;
            }
            long activeMembers = members.stream().filter(FranchiseHqMember::isActive).count();
            long approvedStores = storeRepository.countByBrandNameAndDeletedAtIsNull(brandName);
            long pending = affiliationRepository.countPendingByBrand(brandName);
            FranchiseJoinCode code = joinCodeRepository.findByBrandName(brandName).orElse(null);
            Instant lastLogin = hqMemberRepository.maxLastLoginAtByBrand(brandName);
            result.add(new FranchiseListItem(brandName, brand.getStatus(), members.size(), activeMembers,
                    approvedStores, pending, code != null && code.isActive(),
                    toIso(code == null ? null : code.getRotatedAt()), toIso(lastLogin), toIso(brand.getCreatedAt())));
        }
        return result;
    }

    private boolean matchesQuery(String brandName, List<FranchiseHqMember> members, String needle) {
        if (brandName.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (FranchiseHqMember m : members) {
            AppUser u = appUserRepository.findById(m.getUserId()).orElse(null);
            if (u != null && u.getEmail() != null && u.getEmail().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return storeRepository.findByBrandNameAndDeletedAtIsNull(brandName).stream()
                .anyMatch(s -> s.getName().toLowerCase(Locale.ROOT).contains(needle));
    }

    @Transactional(readOnly = true)
    public FranchiseDetailResponse getFranchise(String brandName) {
        FranchiseBrand brand = requireBrand(brandName);
        List<MemberItem> memberItems = hqMemberRepository.findByBrandNameOrderByInvitedAtAsc(brandName).stream()
                .map(m -> {
                    AppUser u = appUserRepository.findById(m.getUserId()).orElse(null);
                    return new MemberItem(m.getPublicId().toString(), u == null ? null : u.getName(),
                            u == null ? null : u.getEmail(), m.getTitle(), m.getStatus(), toIso(m.getLastLoginAt()),
                            toIso(m.getInvitedAt()), toIso(m.getRevokedAt()));
                }).toList();
        FranchiseJoinCode code = joinCodeRepository.findByBrandName(brandName).orElse(null);
        JoinCodeInfo joinCodeInfo = code == null ? null
                : new JoinCodeInfo(code.isActive(), toIso(code.getRotatedAt()), code.getRotatedRef());
        List<ApprovedStoreItem> storeItems = storeRepository.findByBrandNameAndDeletedAtIsNull(brandName).stream()
                .map(s -> {
                    Instant approvedAt = affiliationRepository.findApprovedByStoreIdOrderByDecidedAtDesc(s.getId())
                            .stream().findFirst().map(FranchiseAffiliationRequest::getDecidedAt).orElse(null);
                    return new ApprovedStoreItem(s.getPublicId().toString(), s.getName(), s.getAddress(),
                            toIso(approvedAt));
                }).toList();
        return new FranchiseDetailResponse(brandName, brand.getStatus(), toIso(brand.getCreatedAt()), joinCodeInfo,
                memberItems, storeItems);
    }

    @Transactional
    public void setStatus(String brandName, String status, String reason, String adminRef) {
        FranchiseBrand brand = requireBrand(brandName);
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        brand.changeStatus(status);
        // ★ SUSPENDED 면 HqAccessGuard 가 요청마다 브랜드 상태를 재확인하므로 그것만으로
        //   그 브랜드의 본부 조회가 즉시 막힌다 — 담당자별 세션을 따로 지울 필요가 없다
        //   (한 담당자가 다른 ACTIVE 브랜드도 겸할 수 있어 세션 자체를 지우면 그 브랜드까지 막힌다).
        auditFranchise("FRANCHISE_STATUS_CHANGED", brandName, "FRANCHISE_BRAND", null, adminRef,
                reason + " (status=" + status + ")");
    }

    /**
     * 계약상 약정 매장 수 설정(V49, 가맹 브랜드 구간 단가). null 이면 약정 해제 — 기본 단가로 돌아간다.
     * 실제 청구 금액 계산은 {@code BrandPricingService} 가 한다, 여기서는 값만 검증·저장한다.
     */
    @Transactional
    public void setCommittedStoreCount(String brandName, Integer committedStoreCount, String reason, String adminRef) {
        FranchiseBrand brand = requireBrand(brandName);
        if (committedStoreCount != null && (committedStoreCount < 1 || committedStoreCount > 100_000)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        brand.changeCommittedStoreCount(committedStoreCount);
        auditFranchise("FRANCHISE_COMMITTED_STORE_COUNT_CHANGED", brandName, "FRANCHISE_BRAND", null, adminRef,
                (reason == null ? "" : reason) + " (committedStoreCount=" + committedStoreCount + ")");
    }

    // ── 시스템 콘솔: 담당자 ─────────────────────────────────────────────────

    @Transactional
    public AddMemberResponse addMember(String brandName, AddMemberRequest req, String adminRef) {
        requireBrand(brandName);
        String email = normalize(req.email());
        AppUser user = appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email)
                .orElseGet(() -> appUserRepository.save(AppUser.builder().email(email).name(req.name().trim()).build()));
        FranchiseHqMember member = hqMemberRepository.findByUserIdAndBrandName(user.getId(), brandName).orElse(null);
        if (member != null) {
            if (member.isActive()) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
            }
            member.activate();
            member.updateProfile(blankToNull(req.title()));
        } else {
            member = hqMemberRepository.save(FranchiseHqMember.builder().userId(user.getId()).brandName(brandName)
                    .title(blankToNull(req.title())).invitedRef(adminRef).build());
        }
        mailService.sendHqInvite(email, brandName);
        auditFranchise("FRANCHISE_MEMBER_ADDED", brandName, "FRANCHISE_MEMBER", member.getId(), adminRef,
                req.reason());
        return new AddMemberResponse(member.getPublicId().toString());
    }

    @Transactional
    public void updateMember(String brandName, UUID memberId, UpdateMemberRequest req, String adminRef) {
        FranchiseHqMember member = requireMember(brandName, memberId);
        if (req.title() != null) {
            member.updateProfile(req.title());
        }
        if (req.name() != null && !req.name().isBlank()) {
            AppUser user = appUserRepository.findById(member.getUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            user.updateProfile(req.name(), user.getPhone());
        }
        if (req.status() != null && !req.status().isBlank()) {
            applyStatus(member, req.status(), adminRef);
        }
        auditFranchise("FRANCHISE_MEMBER_UPDATED", brandName, "FRANCHISE_MEMBER", member.getId(), adminRef,
                req.reason());
    }

    /** DELETE — 물리 삭제가 아니라 REVOKED 전이다(docs/26a endpoints.adminConsole). */
    @Transactional
    public void revokeMember(String brandName, UUID memberId, String reason, String adminRef) {
        FranchiseHqMember member = requireMember(brandName, memberId);
        applyStatus(member, "REVOKED", adminRef);
        auditFranchise("FRANCHISE_MEMBER_REVOKED", brandName, "FRANCHISE_MEMBER", member.getId(), adminRef, reason);
    }

    @Transactional
    public void revokeMemberSessions(String brandName, UUID memberId, String reason, String adminRef) {
        FranchiseHqMember member = requireMember(brandName, memberId);
        sessionService.revokeAllHqSessions(member.getUserId());
        auditFranchise("FRANCHISE_MEMBER_SESSIONS_REVOKED", brandName, "FRANCHISE_MEMBER", member.getId(), adminRef,
                reason);
    }

    private void applyStatus(FranchiseHqMember member, String status, String adminRef) {
        if ("REVOKED".equals(status)) {
            member.revoke(adminRef, Instant.now());
            // ★ 접근 중지는 그 사용자의 본부 세션 전부를 지운다(다른 브랜드 겸직이어도 전부) —
            //   docs/26a endpoints.adminConsole 이 명시한 동작이다. 재로그인하면 남은 브랜드는 다시 보인다.
            sessionService.revokeAllHqSessions(member.getUserId());
        } else if ("ACTIVE".equals(status)) {
            member.activate();
        } else {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }

    // ── 시스템 콘솔: 가맹코드 ───────────────────────────────────────────────

    @Transactional
    public String rotateJoinCode(String brandName, String reason, String adminRef) {
        requireBrand(brandName);
        String rawCode = generateCode();
        FranchiseJoinCode code = joinCodeRepository.findByBrandName(brandName).orElse(null);
        if (code == null) {
            joinCodeRepository.save(FranchiseJoinCode.builder().brandName(brandName).codeHash(hash(rawCode))
                    .rotatedAt(Instant.now()).rotatedRef(adminRef).build());
        } else {
            code.rotate(hash(rawCode), adminRef, Instant.now());
        }
        auditFranchise("FRANCHISE_JOIN_CODE_ROTATED", brandName, "FRANCHISE_JOIN_CODE", null, adminRef, reason);
        return rawCode;
    }

    @Transactional
    public void setJoinCodeStatus(String brandName, boolean active, String reason, String adminRef) {
        FranchiseJoinCode code = joinCodeRepository.findByBrandName(brandName)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        code.changeActive(active);
        auditFranchise("FRANCHISE_JOIN_CODE_STATUS_CHANGED", brandName, "FRANCHISE_JOIN_CODE", null, adminRef,
                reason + " (active=" + active + ")");
    }

    @Transactional(readOnly = true)
    public List<AuditLogItem> listAuditLogs(String brandName, int limit) {
        return auditLogRepository.findByBrandDetail(brandName, limit).stream()
                .map(log -> new AuditLogItem(log.getAction(), log.getActorType(), log.getCreatedAt(), log.getDetail()))
                .toList();
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private FranchiseBrand requireBrand(String brandName) {
        return franchiseBrandRepository.findByBrandName(brandName)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private FranchiseHqMember requireMember(String brandName, UUID memberId) {
        return hqMemberRepository.findByPublicId(memberId)
                .filter(m -> brandName.equals(m.getBrandName()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private FranchiseJoinCode findActiveCode(String rawCode) {
        return joinCodeRepository.findByCodeHashAndActiveTrue(hash(rawCode))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_FRANCHISE_CODE));
    }

    /** ★ 시스템 콘솔의 모든 변경은 사유와 함께 감사로그에 남는다. 원문 가맹코드·OTP 는 절대 담지 않는다. */
    private void auditFranchise(String action, String brandName, String targetType, Long targetId, String adminRef,
            String reason) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("adminRef", adminRef);
        detail.put("brandName", brandName);
        detail.put("reason", reason);
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            json = null;
        }
        auditLogRepository.save(AuditLog.builder().actorType("ADMIN").actorId(null).action(action)
                .targetType(targetType).targetId(targetId).detail(json).build());
    }

    public record AffiliationResponse(String id, String brandName, String requesterName, String requesterEmail,
            String storeName, String storeAddress, java.time.Instant requestedAt) {}

    private static String generateCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            raw.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
    }

    private static String hash(String code) {
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
