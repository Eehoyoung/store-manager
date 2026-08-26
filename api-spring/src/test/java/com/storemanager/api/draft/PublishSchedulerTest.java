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
import com.storemanager.api.store.StoreServiceGate;
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

    @Mock private StoreServiceGate serviceGate;

    private PublishScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PublishScheduler(replyDraftRepository, reviewAnalysisRepository, unifiedReviewRepository,
                storePlatformLinkRepository, storeRepository, auditLogRepository, stringRedisTemplate,
                new ObjectMapper(), serviceGate);
        // 일부 테스트는 게이트 이전에 반환하므로 lenient 로 둔다.
        org.mockito.Mockito.lenient().when(serviceGate.isServiceable(any())).thenReturn(true);
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

    /**
     * ★ 사람이 승인한 고위험 초안은 통과해야 한다(2026-08-27).
     * 방어선을 없앤 게 아니라 조건을 좁혔다 — approved_by 와 risk_ack_at 이 둘 다 있어야 한다.
     * 자동 경로는 이 두 값을 채우지 않으므로 풀자동 게시는 여전히 risk>=3 을 넘지 못한다.
     */
    @Test
    void 사람이_승인한_고위험_초안은_디스패치된다() {
        ReplyDraft draft = ReplyDraft.builder().id(3L).reviewId(12L).storeId(100L).content("답글 내용")
                .status("BLOCKED").generatedBy("AI").build();
        draft.approveByHuman(7L, Instant.now(), null, Instant.now().minusSeconds(60));
        when(replyDraftRepository.findDueForPublish(any(Instant.class), any(Pageable.class))).thenReturn(List.of(draft));
        when(reviewAnalysisRepository.findById(12L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(12L).category("COMPLAINT").sentiment(-0.9f)
                        .riskLevel((short) 3).riskReasons(new String[] {"FOOD_POISONING"}).model("m")
                        .promptVersion("v1").build()));
        safeContext(12L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("dispatch:draft:3"), anyString(), any(Duration.class))).thenReturn(true);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        scheduler.dispatchDuePublishJobs();

        // 차단되지 않고 큐로 나갔다. payload 의 humanApproved 가 true 여야 워커도 통과시킨다.
        assertThat(draft.getStatus()).isEqualTo("SCHEDULED");
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq("q:publish"), body.capture());
        assertThat(body.getValue()).contains("\"humanApproved\":true").contains("\"riskLevel\":3");
    }

    /** 승인 표시가 반쪽만 있으면 통과하지 않는다 — 사유 확인 없는 승인은 승인이 아니다. */
    @Test
    void 사유_확인_없이_승인자만_있으면_여전히_막힌다() {
        // approved_by 만 채우고 risk_ack_at 은 비운 채 SCHEDULED 인, 있을 수 있는 최악의 행
        ReplyDraft draft = ReplyDraft.builder().id(4L).reviewId(13L).storeId(100L).content("답글 내용")
                .status("SCHEDULED").generatedBy("AI").approvedBy(7L).approvedAt(Instant.now())
                .scheduledAt(Instant.now().minusSeconds(60)).build();
        when(replyDraftRepository.findDueForPublish(any(Instant.class), any(Pageable.class))).thenReturn(List.of(draft));
        when(reviewAnalysisRepository.findById(13L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(13L).category("COMPLAINT").sentiment(-0.9f)
                        .riskLevel((short) 3).riskReasons(new String[] {"HYGIENE"}).model("m")
                        .promptVersion("v1").build()));

        scheduler.dispatchDuePublishJobs();

        assertThat(draft.getStatus()).isEqualTo("BLOCKED");
        verify(stringRedisTemplate, never()).opsForValue();
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
