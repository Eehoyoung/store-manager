package com.storemanager.api.franchise;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseDtos.ProvisionRequest;
import com.storemanager.api.franchise.FranchiseDtos.ProvisionResponse;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FranchiseService {

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FranchiseJoinCodeRepository joinCodeRepository;
    private final FranchiseHqMemberRepository hqMemberRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final FranchiseAffiliationRequestRepository affiliationRepository;
    private final StoreRepository storeRepository;

    public FranchiseService(FranchiseJoinCodeRepository joinCodeRepository,
            FranchiseHqMemberRepository hqMemberRepository, AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository, PasswordEncoder passwordEncoder,
            FranchiseAffiliationRequestRepository affiliationRepository, StoreRepository storeRepository) {
        this.joinCodeRepository = joinCodeRepository;
        this.hqMemberRepository = hqMemberRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.affiliationRepository = affiliationRepository;
        this.storeRepository = storeRepository;
    }

    @Transactional
    public ProvisionResponse provision(ProvisionRequest req) {
        String brandName = req.brandName().trim();
        String email = req.hqEmail().trim().toLowerCase(Locale.ROOT);
        if (joinCodeRepository.existsByBrandName(brandName)
                || appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE);
        }

        AppUser hqUser = appUserRepository.save(AppUser.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.hqPassword()))
                .name(req.hqName().trim())
                .phone(blankToNull(req.hqPhone()))
                .build());
        hqMemberRepository.save(FranchiseHqMember.builder()
                .userId(hqUser.getId())
                .brandName(brandName)
                .build());

        String code = generateCode();
        joinCodeRepository.save(FranchiseJoinCode.builder()
                .brandName(brandName)
                .codeHash(hash(code))
                .build());
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("FRANCHISE_PROVISIONED")
                .targetType("APP_USER")
                .targetId(hqUser.getId())
                .build());
        return new ProvisionResponse(brandName, hqUser.getPublicId().toString(), code);
    }

    @Transactional
    public void requestAffiliation(AppUser user, Store store, String rawCode) {
        FranchiseJoinCode code = findActiveCode(rawCode);
        affiliationRepository.save(FranchiseAffiliationRequest.builder()
                .userId(user.getId()).storeId(store.getId()).joinCodeId(code.getId()).build());
    }

    @Transactional(readOnly = true)
    public java.util.List<AffiliationResponse> pendingAffiliations() {
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
    public void decideAffiliation(java.util.UUID requestId, String decision, AppUser admin) {
        boolean approved;
        if ("APPROVE".equals(decision)) approved = true;
        else if ("REJECT".equals(decision)) approved = false;
        else throw new ApiException(ErrorCode.VALIDATION_FAILED);

        FranchiseAffiliationRequest request = affiliationRepository.findByPublicId(requestId)
                .filter(r -> "PENDING".equals(r.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (approved) {
            AppUser user = appUserRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            Store store = storeRepository.findById(request.getStoreId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
            String brand = joinCodeRepository.findById(request.getJoinCodeId())
                    .filter(FranchiseJoinCode::isActive).map(FranchiseJoinCode::getBrandName)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_FRANCHISE_CODE));
            user.assignFranchiseBrand(brand);
            store.assignBrand(brand);
        }
        request.decide(approved, admin.getId());
        auditLogRepository.save(AuditLog.builder().actorId(admin.getId()).actorType("USER")
                .action(approved ? "FRANCHISE_AFFILIATION_APPROVED" : "FRANCHISE_AFFILIATION_REJECTED")
                .targetType("AFFILIATION").targetId(request.getId()).build());
    }

    private FranchiseJoinCode findActiveCode(String rawCode) {
        return joinCodeRepository.findByCodeHashAndActiveTrue(hash(rawCode))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_FRANCHISE_CODE));
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
}
