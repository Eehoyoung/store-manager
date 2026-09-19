package com.storemanager.api.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos.AnalysisOut;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftRequest;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.ai.BannedWordQueryRepository;
import com.storemanager.api.ai.LlmUsageLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.draft.ReplyDraftRepository;
import com.storemanager.api.naver.NaverDtos.DraftRequest;
import com.storemanager.api.naver.NaverDtos.DraftResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.StoreServiceGate;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * NaverDraftService 단위테스트(IMPLEMENTATION_PLAN_NAVER.md §7).
 *
 * <p>★ 이 클래스는 {@code ReplyDraftRepository.save}·{@code UnifiedReviewRepository} 를
 * 구조적으로 호출하지 않는다(생성자에 UnifiedReviewRepository 자체가 없다) — 네이버 경로가
 * 배달 3사 게시 큐(worker/publish.py)에 진입하지 않는다는 격리 요구사항의 증거다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NaverDraftServiceTest {

    @Mock private NaverReviewEventRepository naverReviewEventRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AiClient aiClient;
    @Mock private BannedWordQueryRepository bannedWordQueryRepository;
    @Mock private LlmUsageLogRepository llmUsageLogRepository;
    @Mock private ReplyDraftRepository replyDraftRepository;
    @Mock private StoreServiceGate serviceGate;

    private NaverDraftService service;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final UUID storePublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("o@t.com").name("사장").build();
    private final Store store = Store.builder().id(100L).publicId(storePublicId).ownerId(1L).name("가게").build();
    private final StorePersona persona = StorePersona.builder().storeId(100L).personaSeed(1).build();

    @BeforeEach
    void setUp() {
        service = new NaverDraftService(naverReviewEventRepository, storeRepository, storePersonaRepository,
                appUserRepository, aiClient, bannedWordQueryRepository, llmUsageLogRepository, replyDraftRepository,
                serviceGate);
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        when(bannedWordQueryRepository.findActiveGlobal()).thenReturn(List.of());
        when(replyDraftRepository.findRecentPublishedContents(any(), any(), any())).thenReturn(List.of());
        when(naverReviewEventRepository.findByStoreIdAndReviewHash(any(), any())).thenReturn(Optional.empty());
    }

    private DraftRequest request(String body) {
        return new DraftRequest(storePublicId.toString(), "h".repeat(64), 5, body, null, false);
    }

    @Test
    void 구독_비활성_매장은_AI_호출_전에_차단한다() {
        when(serviceGate.isServiceable(store)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.generateDraft(ownerPublicId, request("좋아요")));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SUBSCRIPTION_INACTIVE);
        verify(aiClient, never()).analyzeAndDraft(any());
    }

    @Test
    void 다른_사장님_매장_storeId로_호출하면_404를_반환한다() {
        Store other = Store.builder().id(100L).publicId(storePublicId).ownerId(999L).name("가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(other));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.generateDraft(ownerPublicId, request("좋아요")));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(aiClient, never()).analyzeAndDraft(any());
    }

    @Test
    void AI_요청_사본에서_전화번호와_이메일이_마스킹된다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("PRAISE", 0), List.of(draftOut("감사합니다")), false, List.of()));

        service.generateDraft(ownerPublicId, request("010-1234-5678로 연락주세요 test@example.com"));

        ArgumentCaptor<AnalyzeAndDraftRequest> captor = ArgumentCaptor.forClass(AnalyzeAndDraftRequest.class);
        verify(aiClient).analyzeAndDraft(captor.capture());
        String maskedBody = captor.getValue().review().body();
        assertThat(maskedBody).doesNotContain("010-1234-5678").doesNotContain("test@example.com");
        assertThat(maskedBody).contains("[전화번호]").contains("[이메일]");
    }

    @Test
    void blocked_응답이어도_naver_review_event_행이_남는다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("COMPLAINT", 3), List.of(draftOut("불편을 드려 죄송합니다")), true,
                        List.of("G8_RISK")));

        DraftResponse res = service.generateDraft(ownerPublicId, request("이물질이 나왔어요"));

        assertThat(res.blocked()).isTrue();
        assertThat(res.draft()).isEqualTo("불편을 드려 죄송합니다");
        ArgumentCaptor<NaverReviewEvent> captor = ArgumentCaptor.forClass(NaverReviewEvent.class);
        verify(naverReviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().isBlocked()).isTrue();
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFTED");
    }

    @Test
    void 네이버_경로는_ReplyDraft를_생성하지_않는다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("PRAISE", 0), List.of(draftOut("감사합니다")), false, List.of()));

        service.generateDraft(ownerPublicId, request("최고예요"));

        // replyDraftRepository 는 G7 중복검사 조회(findRecentPublishedContents)에만 쓰이고
        // save 는 절대 호출되지 않는다 — 네이버 리뷰가 배달 3사 게시 큐로 새지 않는다는 증거.
        verify(replyDraftRepository, never()).save(any());
    }

    private static AnalysisOut analysis(String category, int riskLevel) {
        return new AnalysisOut(category, "NEUTRAL", 0f, List.of(), List.of(), riskLevel, List.of(), "claude-haiku-4-5",
                "v1.8");
    }

    private static DraftOut draftOut(String content) {
        return new DraftOut(content, "T1", "claude-haiku-4-5", "v1.8", List.of(), null, 100, 50, 4.56);
    }
}
