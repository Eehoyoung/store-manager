package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos.AnalysisOut;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.ai.LlmUsageLogRepository;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.DraftDtos.GenerateDraftsRequest;
import com.storemanager.api.notify.Notifier;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DraftService 단위테스트(S12-c, S12-d). AiClient·Repository 는 전부 목으로 대체한다.
 * ★ risk_level>=3 승인 거부(절대규칙 3)와 자동승인 규칙 분기(S8)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    @Mock private ReplyDraftRepository replyDraftRepository;
    @Mock private ReviewAnalysisRepository reviewAnalysisRepository;
    @Mock private UnifiedReviewRepository unifiedReviewRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AiClient aiClient;
    @Mock private LlmUsageLogRepository llmUsageLogRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private Notifier notifier;

    private DraftService draftService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final Store store = Store.builder().id(100L).ownerId(1L).name("가게").build();

    @BeforeEach
    void setUp() {
        draftService = new DraftService(replyDraftRepository, reviewAnalysisRepository, unifiedReviewRepository,
                storeRepository, storePersonaRepository, appUserRepository, aiClient, llmUsageLogRepository,
                auditLogRepository, notifier, new ObjectMapper());
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    @Test
    void 자동승인_조건을_모두_만족하면_초안_생성직후_SCHEDULED로_전이한다() {
        UnifiedReview review = UnifiedReview.builder().id(20L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-20").rating((short) 5).body("맛있어요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findById(20L)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("POSITIVE", 0.9f, List.of(), 0, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("고객님, 감사합니다", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of());
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(20L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, 20L, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts()).hasSize(1);
        assertThat(result.drafts().get(0).status()).isEqualTo("SCHEDULED");
        org.mockito.Mockito.verify(auditLogRepository).save(
                org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "DRAFT_AUTO_SCHEDULED".equals(a.getAction())));
    }

    @Test
    void 풀자동화는_별점과_무관하게_안전한_초안을_SCHEDULED로_전이한다() {
        UnifiedReview review = UnifiedReview.builder().id(21L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-21").rating((short) 3).body("그저 그래요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findById(21L)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("IMPROVEMENT", 0.0f, List.of(), 0, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("고객님, 의견 감사합니다", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of());
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(21L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, 21L, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts().get(0).status()).isEqualTo("SCHEDULED");
        org.mockito.Mockito.verify(auditLogRepository).save(
                org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "DRAFT_AUTO_SCHEDULED".equals(a.getAction())));
    }

    @Test
    void 스텁_분류결과는_풀자동_게시하지_않는다() {
        UnifiedReview review = UnifiedReview.builder().id(24L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-24").rating((short) 5).body("맛있어요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findById(24L)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        AnalysisOut analysis = new AnalysisOut("POSITIVE", 1f, List.of(), 0, List.of(), "stub", "v1");
        when(aiClient.analyzeAndDraft(any())).thenReturn(new AnalyzeAndDraftResponse(analysis,
                List.of(new DraftOut("고객님, 감사합니다", "T1", "stub", "v1", List.of(), 0.1f, 0, 0, 0)),
                false, List.of()));
        when(reviewAnalysisRepository.findById(24L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, 24L, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts().get(0).status()).isEqualTo("BLOCKED");
        assertThat(result.drafts().get(0).guardrailFlags()).containsExactly("AUTOMATION_MODEL_UNAVAILABLE");
    }

    @Test
    void 가드레일_전량차단시_BLOCKED_초안을_저장하고_422를_던진다() {
        UnifiedReview review = UnifiedReview.builder().id(22L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-22").rating((short) 1).body("환불해주세요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY").publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findById(22L)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("COMPLAINT", -0.9f, List.of(), 1, List.of(), "local-7b", "v1");
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(), true,
                List.of("G3_COMPENSATION"));
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(22L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiException ex = assertThrows(ApiException.class,
                () -> draftService.generateDrafts(ownerPublicId, 22L, new GenerateDraftsRequest(1, null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.GUARDRAIL_BLOCKED);
        org.mockito.Mockito.verify(replyDraftRepository).save(
                org.mockito.ArgumentMatchers.argThat((ReplyDraft d) -> "BLOCKED".equals(d.getStatus())));
    }

}
