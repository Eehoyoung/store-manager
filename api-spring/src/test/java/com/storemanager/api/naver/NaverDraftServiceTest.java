package com.storemanager.api.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.storemanager.api.naver.NaverDtos.DraftRequest;
import com.storemanager.api.naver.NaverDtos.DraftResponse;
import com.storemanager.api.notify.Notifier;
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
 * <p>★ 이 클래스는 {@code ReplyDraft}·{@code UnifiedReview} 를 구조적으로 만들지 않는다
 * (생성자에 draft 패키지 리포지토리가 아예 없다) — 네이버 경로가 배달 3사 게시 큐
 * (worker/publish.py)에 진입하지 않는다는 격리 요구사항의 증거다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NaverDraftServiceTest {

    @Mock private NaverReviewEventRepository naverReviewEventRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private com.storemanager.api.store.StoreFactRepository storeFactRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AiClient aiClient;
    @Mock private BannedWordQueryRepository bannedWordQueryRepository;
    @Mock private LlmUsageLogRepository llmUsageLogRepository;
    @Mock private Notifier notifier;
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
                appUserRepository, aiClient, storeFactRepository, bannedWordQueryRepository,
                llmUsageLogRepository, notifier,
                serviceGate);
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(store));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        when(bannedWordQueryRepository.findActiveGlobal()).thenReturn(List.of());
        when(naverReviewEventRepository.findRecentPostedContents(any(), any(), any())).thenReturn(List.of());
        when(naverReviewEventRepository.findByStoreIdAndReviewHash(any(), any())).thenReturn(Optional.empty());
    }

    private DraftRequest request(String body) {
        return new DraftRequest(storePublicId.toString(), "h".repeat(64), 5, body, null, false);
    }

    /**
     * ★ 실기동 비용 누수(2026-09-20). 19건을 돌렸는데 유료 호출이 23건 나갔다.
     * 확장은 페이지가 다시 그려질 때마다 목록 전체를 보내는데, 예전 가드는
     * APPROVED·POSTED 만 걸러 DRAFTED·VIEWED 가 통과했다. 같은 리뷰에 LLM 호출이
     * 반복된다 — 매장·리뷰가 늘수록 그대로 비례한다.
     *
     * <p>확장에도 같은 검사가 있지만 best-effort 다(응답 대기 중 다음 스캔이 끼어들 수
     * 있고, 확장 저장소가 비면 통째로 사라진다). <b>돈을 쓰는 쪽이 막아야 한다.</b>
     */
    @Test
    void 이미_초안이_있는_리뷰는_AI_를_다시_부르지_않는다() {
        for (String status : List.of("DRAFTED", "VIEWED", "EDITED", "APPROVED", "POSTED")) {
            NaverReviewEvent existing = NaverReviewEvent.builder()
                    .storeId(100L).reviewHash("h".repeat(64)).status(status)
                    .draftContent("이미 만들어 둔 초안입니다. 감사합니다.").build();
            when(naverReviewEventRepository.findByStoreIdAndReviewHash(100L, "h".repeat(64)))
                    .thenReturn(Optional.of(existing));
            when(serviceGate.isServiceable(store)).thenReturn(true);

            DraftResponse res = service.generateDraft(ownerPublicId, request("좋아요"));

            assertThat(res.draft()).isEqualTo("이미 만들어 둔 초안입니다. 감사합니다.");
        }
        verify(aiClient, never()).analyzeAndDraft(any());
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
    void G7_중복검사는_배달_게시이력이_아니라_네이버_자신의_POSTED_이력을_쓴다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("PRAISE", 0), List.of(draftOut("감사합니다")), false, List.of()));

        service.generateDraft(ownerPublicId, request("최고예요"));

        // 네이버는 naver_review_event 자신의 POSTED 이력을 조회한다(ReplyDraftRepository 는
        // 생성자에서 아예 사라졌으므로 배달 게시 큐로 새는 경로도 구조적으로 없다).
        verify(naverReviewEventRepository).findRecentPostedContents(eq(100L), any(), any());
    }

    @Test
    void risk_3이상이면_고위험_알림을_발행한다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("ABUSIVE", 3), List.of(draftOut("불편을 드려 죄송합니다")), true,
                        List.of("G8_RISK")));

        service.generateDraft(ownerPublicId, request("고소할 거야"));

        // ★ refType 을 배달(UNIFIED_REVIEW)과 다르게 둔다 — uq_notification_high_risk_ref 의
        //   (template, ref_type, ref_id) 유니크 제약이 서로 다른 테이블의 내부 id 를 같은 키로
        //   오인해 충돌하지 않는지가 이 값으로 갈린다.
        verify(notifier).send(eq(1L), eq(100L), eq("ALIMTALK"), eq("HIGH_RISK_REVIEW"), eq("NAVER_REVIEW_EVENT"),
                any());
    }

    @Test
    void risk_3미만이면_알림을_발행하지_않는다() {
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(aiClient.analyzeAndDraft(any())).thenReturn(
                new AnalyzeAndDraftResponse(analysis("COMPLAINT", 2), List.of(draftOut("불편을 드려 죄송합니다")), false,
                        List.of()));

        service.generateDraft(ownerPublicId, request("배달이 너무 늦었어요"));

        verify(notifier, never()).send(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 위험_사유가_이벤트와_응답에_함께_남는다() {
        // ★ "위험도 3" 만으로는 사장님이 무엇을 조심해야 하는지 알 수 없다. 위생 지적과
        //   협박은 할 일이 완전히 다르다. 확장은 이 사유로 경고 배너를 띄운다 —
        //   여기가 끊기면 사장님은 아무 경고 없이 고위험 초안을 그대로 올리게 된다.
        when(aiClient.analyzeAndDraft(any())).thenReturn(new AnalyzeAndDraftResponse(
                new AnalysisOut("COMPLAINT", "CALM", -0.9f, List.of("청결"), List.of(), 3,
                        List.of("HYGIENE"), "claude-haiku-4-5", "naver-v0.4"),
                List.of(draftOut("불편을 드려 죄송합니다. 오늘 바로 확인해 보겠습니다.")), true, List.of("G8_RISK")));
        when(serviceGate.isServiceable(store)).thenReturn(true);

        DraftResponse res = service.generateDraft(ownerPublicId, request("화장실이 너무 더러웠어요"));

        assertThat(res.riskReasons()).containsExactly("HYGIENE");
        assertThat(res.draft()).isNotBlank();   // 초안은 준다 — 게시는 사장님이 직접 누른다
        assertThat(res.bulkApprovable()).isFalse();

        ArgumentCaptor<NaverReviewEvent> captor = ArgumentCaptor.forClass(NaverReviewEvent.class);
        verify(naverReviewEventRepository).save(captor.capture());
        assertThat(captor.getValue().getRiskReasons()).containsExactly("HYGIENE");
    }

    private static AnalysisOut analysis(String category, int riskLevel) {
        return new AnalysisOut(category, "NEUTRAL", 0f, List.of(), List.of(), riskLevel, List.of(), "claude-haiku-4-5",
                "v1.8");
    }

    private static DraftOut draftOut(String content) {
        return new DraftOut(content, "T1", "claude-haiku-4-5", "v1.8", List.of(), null, 100, 50, 4.56);
    }
}
