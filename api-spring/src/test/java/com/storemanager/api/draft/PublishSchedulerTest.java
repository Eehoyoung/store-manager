package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.review.StorePlatformLink;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * PublishScheduler 단위테스트(S9, S12-b 방어검증). Redis 는 목으로 대체하고 실제 연결하지 않는다.
 * ★ risk_level>=3 방어적 이중검증(절대규칙 3)과 dispatch:draft:{id} 중복 방지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PublishSchedulerTest {

    @Mock private ReplyDraftRepository replyDraftRepository;
    @Mock private ReviewAnalysisRepository reviewAnalysisRepository;
    @Mock private UnifiedReviewRepository unifiedReviewRepository;
    @Mock private StorePlatformLinkRepository storePlatformLinkRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ListOperations<String, String> listOperations;

    private PublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PublishScheduler(replyDraftRepository, reviewAnalysisRepository, unifiedReviewRepository,
                storePlatformLinkRepository, storeRepository, auditLogRepository, stringRedisTemplate,
                new ObjectMapper());
    }

    private ReplyDraft dueDraft(long id, long reviewId) {
        return ReplyDraft.builder().id(id).reviewId(reviewId).storeId(100L).content("답글 내용")
                .status("SCHEDULED").generatedBy("AI").scheduledAt(Instant.now().minusSeconds(60)).build();
    }

    private void safeContext(long reviewId) {
        when(unifiedReviewRepository.findById(reviewId)).thenReturn(Optional.of(UnifiedReview.builder()
                .id(reviewId).storeId(100L).linkId(5L).platform("BAEMIN")
                .platformReviewId("plat-review-1").writtenAt(Instant.now()).build()));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(Store.builder().id(100L).ownerId(1L)
                .name("매장").status("ACTIVE").activatedAt(Instant.now()).build()));
        when(storePlatformLinkRepository.findById(5L)).thenReturn(Optional.of(StorePlatformLink.builder()
                .id(5L).storeId(100L).accountId(77L).platform("BAEMIN").platformStoreId("store-77").build()));
    }

    @Test
    void risk_level이_3이상으로_재확인되면_디스패치하지_않고_BLOCKED로_되돌린다() {
        ReplyDraft draft = dueDraft(1L, 10L);
        when(replyDraftRepository.findDueForPublish(any(Instant.class), any(Pageable.class))).thenReturn(List.of(draft));
        when(reviewAnalysisRepository.findById(10L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(10L).category("COMPLAINT").sentiment(-0.9f)
                        .riskLevel((short) 3).riskReasons(new String[] {"FOOD_POISONING"}).model("m")
                        .promptVersion("v1").build()));

        scheduler.dispatchDuePublishJobs();

        assertThat(draft.getStatus()).isEqualTo("BLOCKED");
        verify(stringRedisTemplate, never()).opsForValue();
        verify(auditLogRepository).save(any());
    }

    @Test
    void 정상건은_dispatch_키를_선점한뒤_qpublish로_LPUSH한다() {
        ReplyDraft draft = dueDraft(2L, 11L);
        when(replyDraftRepository.findDueForPublish(any(Instant.class), any(Pageable.class))).thenReturn(List.of(draft));
        when(reviewAnalysisRepository.findById(11L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(11L).category("PRAISE").sentiment(0.9f)
                        .riskLevel((short) 0).model("m").promptVersion("v1").build()));
        safeContext(11L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("dispatch:draft:2"), anyString(), any(Duration.class))).thenReturn(true);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        scheduler.dispatchDuePublishJobs();

        verify(listOperations).leftPush(eq("q:publish"), anyString());
    }

    @Test
    void 이미_디스패치된_잡은_중복으로_LPUSH하지_않는다() {
        ReplyDraft draft = dueDraft(3L, 12L);
        when(replyDraftRepository.findDueForPublish(any(Instant.class), any(Pageable.class))).thenReturn(List.of(draft));
        when(reviewAnalysisRepository.findById(12L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(12L).category("PRAISE").sentiment(0.9f)
                        .riskLevel((short) 0).model("m").promptVersion("v1").build()));
        safeContext(12L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("dispatch:draft:3"), anyString(), any(Duration.class))).thenReturn(false);

        scheduler.dispatchDuePublishJobs();

        verify(stringRedisTemplate, times(0)).opsForList();
    }
}
