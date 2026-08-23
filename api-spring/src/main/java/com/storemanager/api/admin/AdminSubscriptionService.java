package com.storemanager.api.admin;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자의 구독 활성화·정지 (Groble 결제 연동 전까지의 수동 경로).
 *
 * <p>★ 2026-08-23 결정: <b>입금을 확인한 뒤에만 ACTIVE 로 올린다.</b> 가입만으로는 서비스하지 않는다.
 * Groble 연동이 되면 이 전이가 자동화될 뿐 게이트와 상태 모델은 그대로다.
 *
 * <p>★ 활성화는 그 매장에 DataAPI 호출과 LLM 토큰을 쓰기 시작한다는 뜻이다. 즉 <b>돈이 나가는
 * 결정</b>이므로 누가 언제 무엇을 근거로 했는지 감사로그에 남긴다. 이 기록을 지우지 말 것 —
 * 요금 분쟁이 생기면 이것이 유일한 근거다.
 */
@Service
public class AdminSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSubscriptionService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal PRICE_KRW = new BigDecimal("30000");

    private final StoreRepository storeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AdminSubscriptionService(StoreRepository storeRepository,
            SubscriptionRepository subscriptionRepository, AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.storeRepository = storeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /** 매장별 서비스 상태 목록. 운영자가 무엇을 결정해야 하는지 한 화면에서 보이게 한다. */
    @Transactional(readOnly = true)
    public List<StoreServiceRow> list() {
        return storeRepository.findByDeletedAtIsNullOrderByIdAsc().stream()
                .map(store -> {
                    Subscription sub = subscriptionRepository
                            .findByStoreIdAndStatusNot(store.getId(), "CANCELED").orElse(null);
                    AppUser owner = appUserRepository.findById(store.getOwnerId()).orElse(null);
                    return new StoreServiceRow(
                            store.getPublicId().toString(),
                            store.getName(),
                            owner == null ? null : owner.getName(),
                            owner == null ? null : owner.getEmail(),
                            store.getActivatedAt() != null,
                            sub == null ? null : sub.getStatus(),
                            sub == null ? null : sub.getCurrentPeriodEnd(),
                            // 실제로 서비스 중인가 — 두 게이트를 모두 통과해야 한다.
                            store.getActivatedAt() != null && "ACTIVE".equals(store.getStatus())
                                    && sub != null && "ACTIVE".equals(sub.getStatus()));
                })
                .toList();
    }

    /** 입금 확인 → 구독 활성화. 구독 행이 없으면 만든다. */
    @Transactional
    public void activate(UUID storePublicId, String note, Long adminUserId) {
        Store store = loadStore(storePublicId);
        if (store.getActivatedAt() == null) {
            // 전자계약이 없는 매장을 활성화하면 법적 근거 없이 데이터를 다루게 된다.
            throw new ApiException(ErrorCode.CONTRACT_NOT_SIGNED,
                    Map.of("reason", "전자계약이 완료되지 않은 매장입니다."));
        }
        Instant now = Instant.now();
        Subscription sub = subscriptionRepository
                .findByStoreIdAndStatusNot(store.getId(), "CANCELED")
                .orElseGet(() -> Subscription.builder()
                        .storeId(store.getId())
                        .priceKrw(PRICE_KRW)
                        .build());
        sub.activateByOperator(now, now.atZone(KST).plusMonths(1).toInstant());
        subscriptionRepository.save(sub);
        audit(adminUserId, "SUBSCRIPTION_ACTIVATED", store.getId(), note);
        log.info("구독 활성화 storeId={} adminUserId={}", store.getId(), adminUserId);
    }

    /** 서비스 정지. 해지가 아니라 정지다 — 재개할 수 있다. */
    @Transactional
    public void suspend(UUID storePublicId, String note, Long adminUserId) {
        Store store = loadStore(storePublicId);
        Subscription sub = subscriptionRepository
                .findByStoreIdAndStatusNot(store.getId(), "CANCELED")
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        sub.suspend();
        audit(adminUserId, "SUBSCRIPTION_SUSPENDED", store.getId(), note);
        log.info("구독 정지 storeId={} adminUserId={}", store.getId(), adminUserId);
    }

    private Store loadStore(UUID storePublicId) {
        return storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void audit(Long adminUserId, String action, Long storeId, String note) {
        auditLogRepository.save(AuditLog.builder()
                .actorType("ADMIN")
                .actorId(adminUserId)
                .action(action)
                .targetType("STORE")
                .targetId(storeId)
                // ★ 입금 확인 근거(입금자명·일자 등)를 남긴다. 요금 분쟁 시 유일한 근거다.
                .detail(toDetailJson(note))
                .build());
    }

    /** 감사로그 detail 은 jsonb 다. 운영자가 적은 근거를 문자열로 밀어 넣으면 파싱이 깨진다. */
    private String toDetailJson(String note) {
        try {
            return objectMapper.writeValueAsString(Map.of("note", note == null ? "" : note));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    public record StoreServiceRow(String storeId, String storeName, String ownerName, String ownerEmail,
            boolean contractSigned, String subscriptionStatus, Instant currentPeriodEnd, boolean serviceActive) {
    }
}
