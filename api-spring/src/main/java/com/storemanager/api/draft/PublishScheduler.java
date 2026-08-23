package com.storemanager.api.draft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시 스케줄러(S9). SCHEDULED 이고 scheduled_at 이 지난 초안을 최대 100건씩 배치로 골라
 * risk_level 을 방어적으로 재검증한 뒤 Redis 'q:publish' 로 디스패치한다(오케스트레이터 고정계약).
 * ★ 절대규칙 3 이중검증: 승인 시점 이후 재분석 등으로 risk_level 이 바뀌었을 가능성에 대비한 마지막 방어선.
 * 기본 활성화, app.scheduler.publish.enabled=false 로 끌 수 있다(테스트 프로파일은 이 값을 지정하지 않아 비활성).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.publish.enabled", havingValue = "true")
public class PublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduler.class);
    private static final short RISK_BLOCK_LEVEL = 3; // CLAUDE.md 절대규칙 3
    private static final String DISPATCH_KEY_PREFIX = "dispatch:draft:";
    private static final String QUEUE_KEY = "q:publish";
    private static final Duration DISPATCH_TTL = Duration.ofSeconds(900);
    private static final int BATCH_SIZE = 100;

    private final ReplyDraftRepository replyDraftRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final UnifiedReviewRepository unifiedReviewRepository;
    private final StorePlatformLinkRepository storePlatformLinkRepository;
    private final StoreRepository storeRepository;
    private final AuditLogRepository auditLogRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final StoreServiceGate serviceGate;

    public PublishScheduler(ReplyDraftRepository replyDraftRepository,
            ReviewAnalysisRepository reviewAnalysisRepository, UnifiedReviewRepository unifiedReviewRepository,
            StorePlatformLinkRepository storePlatformLinkRepository, StoreRepository storeRepository,
            AuditLogRepository auditLogRepository,
            StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
            StoreServiceGate serviceGate) {
        this.replyDraftRepository = replyDraftRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.unifiedReviewRepository = unifiedReviewRepository;
        this.storePlatformLinkRepository = storePlatformLinkRepository;
        this.storeRepository = storeRepository;
        this.auditLogRepository = auditLogRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.serviceGate = serviceGate;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dispatchDuePublishJobs() {
        List<ReplyDraft> due = replyDraftRepository.findDueForPublish(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (ReplyDraft draft : due) {
            dispatchOne(draft);
        }
    }

    private void dispatchOne(ReplyDraft draft) {
        ReviewAnalysis analysis = reviewAnalysisRepository.findById(draft.getReviewId()).orElse(null);
        if (analysis == null) {
            // ★ 절대규칙 3 은 위험도를 '모를 때' 안전측으로 기울어야 성립한다.
            // 분석 행이 없다는 것은 risk_level=0 이라는 뜻이 아니라 위험도를 알 수 없다는 뜻이므로
            // 게시하지 않는다. 여기서 0 으로 간주하면 워커의 안전 기본값(riskLevel 누락 시 차단)도
            // Spring 이 0 을 명시해 보내는 탓에 무력화된다.
            draft.blockForScheduling("ANALYSIS_MISSING");
            auditBlocked(draft, "DRAFT_BLOCKED_ANALYSIS_MISSING");
            return;
        }
        int riskLevel = analysis.getRiskLevel();

        if (riskLevel >= RISK_BLOCK_LEVEL) {
            draft.blockForRisk(List.of(analysis.getRiskReasons()));
            auditBlocked(draft, "DRAFT_BLOCKED_RISK_RECHECK");
            return;
        }

        UnifiedReview review = unifiedReviewRepository.findById(draft.getReviewId()).orElse(null);
        if (review == null) {
            draft.blockForScheduling("REVIEW_MISSING");
            auditBlocked(draft, "DRAFT_BLOCKED_REVIEW_MISSING");
            return;
        }
        Store store = storeRepository.findById(draft.getStoreId()).orElse(null);
        // ★ 계약(activated_at)뿐 아니라 구독까지 본다. 게시 1건은 DataAPI 호출 1회 = 과금이다.
        //   미납 매장의 답글을 계속 올리면 못 받을 돈을 우리가 대신 내는 셈이다.
        if (!serviceGate.isServiceable(store)) {
            draft.blockForScheduling("STORE_INACTIVE");
            auditBlocked(draft, "DRAFT_BLOCKED_STORE_INACTIVE");
            return;
        }
        StorePlatformLink link = storePlatformLinkRepository.findById(review.getLinkId()).orElse(null);
        if (link == null || !link.getStoreId().equals(draft.getStoreId())) {
            draft.blockForScheduling("PLATFORM_LINK_INVALID");
            auditBlocked(draft, "DRAFT_BLOCKED_PLATFORM_LINK_INVALID");
            return;
        }

        String dispatchKey = DISPATCH_KEY_PREFIX + draft.getId();
        String dispatchToken = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(dispatchKey, dispatchToken, DISPATCH_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return; // 이미 디스패치된 잡 — 중복 발행 방지
        }

        PublishJobPayload payload = new PublishJobPayload(draft.getId(), link.getAccountId(), review.getPlatform(),
                link.getPlatformStoreId(), review.getPlatformReviewId(), draft.getContent(), riskLevel, true,
                dispatchToken);
        try {
            stringRedisTemplate.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("게시잡 직렬화 실패 (draftId={}): {}", draft.getId(), e.getMessage());
        }
    }

    private void auditBlocked(ReplyDraft draft, String action) {
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action(action)
                .targetType("REPLY_DRAFT")
                .targetId(draft.getId())
                .build());
    }
}
