package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.BannedWordQueryRepository;
import com.storemanager.api.ai.AiClientDtos.AnalysisOut;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftRequest;
import com.storemanager.api.ai.AiClientDtos.BannedWordIn;
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
import com.storemanager.api.store.StoreServiceGate;
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
import org.mockito.ArgumentCaptor;
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
    @Mock private BannedWordQueryRepository bannedWordQueryRepository;
    @Mock private LlmUsageLogRepository llmUsageLogRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private Notifier notifier;

    @Mock private StoreServiceGate serviceGate;

    private DraftService draftService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final Store store = Store.builder().id(100L).ownerId(1L).name("가게").build();

    @BeforeEach
    void setUp() {
        draftService = new DraftService(replyDraftRepository, reviewAnalysisRepository, unifiedReviewRepository,
                storeRepository, storePersonaRepository, appUserRepository, aiClient, bannedWordQueryRepository,
                llmUsageLogRepository, auditLogRepository, notifier, new ObjectMapper(), serviceGate);
        // 기본은 서비스 가능. 게이트 자체는 아래 전용 테스트에서 확인한다.
        org.mockito.Mockito.lenient().when(serviceGate.isServiceable(any())).thenReturn(true);
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 자동승인_조건을_만족하면_게시이력_조회실패에도_SCHEDULED로_전이한다(boolean historyFailure) {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(20L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-20").rating((short) 5).body("맛있어요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        when(bannedWordQueryRepository.findActiveGlobal())
                .thenReturn(List.of(new BannedWordIn("치료", "MEDICAL", "CONTAINS")));
        if (historyFailure) {
            when(replyDraftRepository.findRecentPublishedContents(any(), any(), any()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("조회 장애 대역"));
        } else {
            when(replyDraftRepository.findRecentPublishedContents(any(), any(), any()))
                    .thenReturn(List.of("최근 게시 답글"));
        }

        AnalysisOut analysisOut = new AnalysisOut("POSITIVE", "CALM", 0.9f, List.of(), List.of(), 0, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("고객님, 감사합니다", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of());
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(20L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts()).hasSize(1);
        assertThat(result.drafts().get(0).status()).isEqualTo("SCHEDULED");
        ArgumentCaptor<AnalyzeAndDraftRequest> requestCaptor = ArgumentCaptor.forClass(AnalyzeAndDraftRequest.class);
        org.mockito.Mockito.verify(aiClient).analyzeAndDraft(requestCaptor.capture());
        assertThat(requestCaptor.getValue().recentReplies())
                .isEqualTo(historyFailure ? List.of() : List.of("최근 게시 답글"));
        org.mockito.Mockito.verify(replyDraftRepository).findRecentPublishedContents(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.argThat(since -> since.isAfter(Instant.now().minusSeconds(30 * 86400L + 10))
                        && since.isBefore(Instant.now().minusSeconds(29 * 86400L))),
                org.mockito.ArgumentMatchers.eq(org.springframework.data.domain.PageRequest.of(0, 20)));
        assertThat(requestCaptor.getValue().persona().globalBannedWords())
                .containsExactly(new BannedWordIn("치료", "MEDICAL", "CONTAINS"));
        org.mockito.Mockito.verify(auditLogRepository).save(
                org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "DRAFT_AUTO_SCHEDULED".equals(a.getAction())));
    }

    @Test
    void AI요청에는_전화번호등_식별자가_마스킹되고_리뷰원문은_그대로_남는다() {
        UUID reviewPublicId = UUID.randomUUID();
        String rawBody = "배달기사님이 늦어서 010-1234-5678 로 전화했어요";
        UnifiedReview review = UnifiedReview.builder().id(25L).publicId(reviewPublicId).storeId(100L).linkId(1L)
                .platform("BAEMIN").platformReviewId("r-25").rating((short) 3).body(rawBody)
                .orderedMenus("[\"메뉴문의는 02-1234-5678 로\"]").writtenAt(Instant.now()).collectedAt(Instant.now())
                .build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY")
                .signature("문의: 010-9999-8888").delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("COMPLAINT", "CALM", -0.2f, List.of(), List.of(), 1, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("불편을 드려 죄송합니다", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of());
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(25L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, "제 번호는 010-1111-2222"));

        ArgumentCaptor<AnalyzeAndDraftRequest> requestCaptor = ArgumentCaptor.forClass(AnalyzeAndDraftRequest.class);
        org.mockito.Mockito.verify(aiClient).analyzeAndDraft(requestCaptor.capture());
        AnalyzeAndDraftRequest sent = requestCaptor.getValue();
        assertThat(sent.review().body()).isEqualTo("배달기사님이 늦어서 [전화번호] 로 전화했어요");
        assertThat(sent.review().menus()).containsExactly("메뉴문의는 [전화번호] 로");
        assertThat(sent.persona().signature()).isEqualTo("문의: [전화번호]");
        assertThat(sent.options().instruction()).isEqualTo("제 번호는 [전화번호]");
        // ★ 원본(unified_review.body)은 마스킹 사본과 무관하게 그대로 남는다.
        assertThat(review.getBody()).isEqualTo(rawBody);
    }

    @Test
    void 풀자동화는_별점과_무관하게_안전한_초안을_SCHEDULED로_전이한다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(21L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-21").rating((short) 3).body("그저 그래요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY")
                .delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("IMPROVEMENT", "CALM", 0.0f, List.of(), List.of(), 0, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("고객님, 의견 감사합니다", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of());
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(21L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts().get(0).status()).isEqualTo("SCHEDULED");
        org.mockito.Mockito.verify(auditLogRepository).save(
                org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "DRAFT_AUTO_SCHEDULED".equals(a.getAction())));
    }

    @Test
    void 스텁_분류결과는_풀자동_게시하지_않는다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(24L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-24").rating((short) 5).body("맛있어요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).delayHours((short) 0).publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        AnalysisOut analysis = new AnalysisOut("POSITIVE", "CALM", 1f, List.of(), List.of(), 0, List.of(), "stub", "v1");
        when(aiClient.analyzeAndDraft(any())).thenReturn(new AnalyzeAndDraftResponse(analysis,
                List.of(new DraftOut("고객님, 감사합니다", "T1", "stub", "v1", List.of(), 0.1f, 0, 0, 0)),
                false, List.of()));
        when(reviewAnalysisRepository.findById(24L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, null));

        assertThat(result.drafts().get(0).status()).isEqualTo("BLOCKED");
        assertThat(result.drafts().get(0).guardrailFlags()).containsExactly("AUTOMATION_MODEL_UNAVAILABLE");
    }

    @Test
    void 가드레일_전량차단시_BLOCKED_초안을_저장하고_422를_던진다() {
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(22L).publicId(reviewPublicId).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-22").rating((short) 1).body("환불해주세요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY").publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        AnalysisOut analysisOut = new AnalysisOut("COMPLAINT", "CALM", -0.9f, List.of(), List.of(), 1, List.of(), "local-7b", "v1");
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut, List.of(), true,
                List.of("G3_COMPENSATION"));
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(22L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiException ex = assertThrows(ApiException.class,
                () -> draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.GUARDRAIL_BLOCKED);
        org.mockito.Mockito.verify(replyDraftRepository).save(
                org.mockito.ArgumentMatchers.argThat((ReplyDraft d) -> "BLOCKED".equals(d.getStatus())));
    }

    @Test
    void 위험초안은_내용을_지우지_않고_승인가능한_플래그로_BLOCKED_된다() {
        // ★ 2026-09-19: blocked=true 라고 초안이 없는 것이 아니다. risk>=3 은 "권장 답글을
        //   만들어 두고 자동 게시만 하지 않는" 경로(2026-08-27)인데, 여기서 내용을 빈 문자열로
        //   덮어쓰고 있었다. RiskApprovalService 가 "내용이 없는 초안은 승인할 수 없습니다" 로
        //   거부하므로 AI 경로로 만들어진 위험 초안은 단 한 건도 승인될 수 없었다.
        UUID reviewPublicId = UUID.randomUUID();
        UnifiedReview review = UnifiedReview.builder().id(23L).publicId(reviewPublicId).storeId(100L).linkId(1L)
                .platform("BAEMIN").platformReviewId("r-23").rating((short) 1)
                .body("씹는데 이상한 게 걸려서 뱉어보니 플라스틱 같더라고요").writtenAt(Instant.now())
                .collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY").publishWindows("[]")
                .personaSeed(1).build();
        when(unifiedReviewRepository.findByPublicId(reviewPublicId)).thenReturn(Optional.of(review));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));

        String recommended = "놀라셨을 텐데 죄송합니다. 어떤 상황이었는지 확인하고 바로 연락드리겠습니다.";
        AnalysisOut analysisOut = new AnalysisOut("COMPLAINT", "CALM", -0.9f, List.of("이물질"), List.of(), 3,
                List.of("FOREIGN_OBJECT"), "claude-haiku-4-5", "v2.2");
        AnalyzeAndDraftResponse aiResponse = new AnalyzeAndDraftResponse(analysisOut,
                List.of(new DraftOut(recommended, "T3", "claude-opus-5", "v2.2", List.of(), 0.1f, 10, 5, 1.0)),
                true, List.of("G8_RISK"));
        when(aiClient.analyzeAndDraft(any())).thenReturn(aiResponse);
        when(reviewAnalysisRepository.findById(23L)).thenReturn(Optional.empty());
        when(replyDraftRepository.save(any(ReplyDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        draftService.generateDrafts(ownerPublicId, reviewPublicId, new GenerateDraftsRequest(1, null));

        ArgumentCaptor<ReplyDraft> captor = ArgumentCaptor.forClass(ReplyDraft.class);
        org.mockito.Mockito.verify(replyDraftRepository).save(captor.capture());
        ReplyDraft saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo(recommended);   // 내용이 살아 있어야 승인 화면이 산다
        assertThat(saved.getStatus()).isEqualTo("BLOCKED");      // 절대규칙 3 — 자동 게시는 여전히 금지
        assertThat(saved.getGuardrailFlags()).containsExactly("RISK_LEVEL_TOO_HIGH");
    }

}
