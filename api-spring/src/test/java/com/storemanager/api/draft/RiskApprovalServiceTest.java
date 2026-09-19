package com.storemanager.api.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 위험 초안 사람 승인 단위테스트.
 *
 * <p>★ 이 파일이 지키는 것은 하나다 — <b>승인 경로는 위험 사유로만 막힌 초안에만 열린다.</b>
 * 가드레일이 잡은 건(금전 보상 약속·개인정보·금칙어)이 여기로 새어 나가면 절대규칙 4 가
 * 사람 승인 한 번으로 무력해진다. 이 테스트가 깨지면 조건을 느슨하게 만들지 말고
 * 왜 그 초안이 승인 대상이 되었는지부터 보라.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskApprovalServiceTest {

    @Mock private ReplyDraftRepository replyDraftRepository;
    @Mock private UnifiedReviewRepository unifiedReviewRepository;
    @Mock private ReviewAnalysisRepository reviewAnalysisRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private StoreServiceGate serviceGate;
    @Mock private com.storemanager.api.review.ReplyStyleSampleRepository replyStyleSampleRepository;

    private RiskApprovalService service;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final UUID draftPublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RiskApprovalService(replyDraftRepository, unifiedReviewRepository, reviewAnalysisRepository,
                storeRepository, storePersonaRepository, appUserRepository, auditLogRepository, serviceGate,
                replyStyleSampleRepository);

        AppUser owner = AppUser.builder().id(7L).publicId(ownerPublicId).email("o@t.com").name("사장").build();
        Store store = Store.builder().id(100L).ownerId(7L).name("시연점").build();
        UnifiedReview review = UnifiedReview.builder().id(10L).storeId(100L).linkId(1L)
                .platform("BAEMIN").platformReviewId("RV1").collectedAt(Instant.now()).build();
        StorePersona persona = StorePersona.builder().storeId(100L).delayHours((short) 0).build();

        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(storeRepository.findById(100L)).thenReturn(Optional.of(store));
        when(unifiedReviewRepository.findById(10L)).thenReturn(Optional.of(review));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        when(serviceGate.isServiceable(store)).thenReturn(true);
        when(reviewAnalysisRepository.findById(10L)).thenReturn(Optional.of(
                ReviewAnalysis.builder().reviewId(10L).category("COMPLAINT").sentiment(-1f)
                        .riskLevel((short) 3).model("claude-opus-5").promptVersion("v1.5").build()));
        when(auditLogRepository.save(org.mockito.ArgumentMatchers.any(AuditLog.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    private ReplyDraft blocked(String... flags) {
        ReplyDraft d = ReplyDraft.builder().id(1L).publicId(draftPublicId).reviewId(10L).storeId(100L)
                .content("고객님, 불편을 드려 죄송합니다. 확인 후 연락드리겠습니다.")
                .status("BLOCKED").generatedBy("AI").guardrailFlags(flags).build();
        when(replyDraftRepository.findByPublicId(draftPublicId)).thenReturn(Optional.of(d));
        when(replyDraftRepository.save(d)).thenReturn(d);
        return d;
    }

    // ── 허용되는 경우 ────────────────────────────────────────────────────

    @Test
    void 위험도만으로_막힌_초안은_사유_확인_후_승인된다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");

        service.approve(ownerPublicId, draftPublicId, true, null);

        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.isHumanApproved()).isTrue();
    }

    /**
     * ★ D-5: 게시 스케줄러 재검증(PublishScheduler.blockForRisk)으로 차단된 초안도
     * 사람이 승인할 수 있어야 한다. 이전에는 blockForRisk 가 guardrailFlags 를
     * riskReasons(FOOD_POISONING 등)로 덮어써 APPROVABLE_FLAG 단일 매칭에 걸려
     * 영원히 승인할 수 없었다.
     */
    @Test
    void 게시스케줄러_재검증으로_BLOCKED된_초안도_승인할_수_있다() {
        ReplyDraft d = ReplyDraft.builder().id(1L).publicId(draftPublicId).reviewId(10L).storeId(100L)
                .content("고객님, 불편을 드려 죄송합니다. 확인 후 연락드리겠습니다.")
                .status("SCHEDULED").generatedBy("AI").build();
        d.blockForRisk(); // PublishScheduler 가 게시 직전 재검증에서 호출하는 것과 동일한 경로
        when(replyDraftRepository.findByPublicId(draftPublicId)).thenReturn(Optional.of(d));
        when(replyDraftRepository.save(d)).thenReturn(d);

        service.approve(ownerPublicId, draftPublicId, true, null);

        assertThat(d.getStatus()).isEqualTo("SCHEDULED");
        assertThat(d.isHumanApproved()).isTrue();
    }

    @Test
    void 사람이_고쳐서_승인하면_원문이_남는다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");

        service.approve(ownerPublicId, draftPublicId, true, "직접 쓴 답글입니다.");

        assertThat(d.getContent()).isEqualTo("직접 쓴 답글입니다.");
        assertThat(d.getOriginalContent()).startsWith("고객님");
        assertThat(d.getGeneratedBy()).isEqualTo("AI_EDITED");
    }

    // ── 막아야 하는 경우 ─────────────────────────────────────────────────

    /** ★ 화면의 체크박스만 믿으면 API 직접 호출로 우회된다. 서버가 직접 막아야 한다. */
    @Test
    void 사유를_확인하지_않으면_승인되지_않는다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");

        ApiException e = assertThrows(ApiException.class,
                () -> service.approve(ownerPublicId, draftPublicId, false, null));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    /**
     * ★ 이 테스트가 이 파일의 핵심이다.
     * 금전 보상·개인정보·금칙어로 막힌 초안은 사람이 승인해도 게시되지 않는다.
     * 그건 상황이 민감한 게 아니라 내용이 규칙을 어긴 것이다.
     */
    @Test
    void 가드레일로_막힌_초안은_사람이_승인해도_게시되지_않는다() {
        for (String flag : List.of("G2_MONEY", "G3_PII", "G5_BANNED_WORD", "AUTOMATION_MODEL_UNAVAILABLE",
                "ABUSIVE_MANUAL_REVIEW", "GENERATION_FAILED")) {
            ReplyDraft d = blocked(flag);

            ApiException e = assertThrows(ApiException.class,
                    () -> service.approve(ownerPublicId, draftPublicId, true, null), flag);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GUARDRAIL_BLOCKED);
            assertThat(d.getStatus()).isEqualTo("BLOCKED");
        }
    }

    /** 위험도 + 가드레일이 함께 걸린 초안도 막는다 — 하나라도 내용 문제면 승인 대상이 아니다. */
    @Test
    void 위험도와_가드레일이_함께_걸리면_승인되지_않는다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH", "G2_MONEY");

        assertThrows(ApiException.class, () -> service.approve(ownerPublicId, draftPublicId, true, null));

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void 내용이_빈_초안은_승인되지_않는다() {
        ReplyDraft d = ReplyDraft.builder().id(1L).publicId(draftPublicId).reviewId(10L).storeId(100L)
                .content("").status("BLOCKED").generatedBy("AI")
                .guardrailFlags(new String[] {"RISK_LEVEL_TOO_HIGH"}).build();
        when(replyDraftRepository.findByPublicId(draftPublicId)).thenReturn(Optional.of(d));

        assertThrows(ApiException.class, () -> service.approve(ownerPublicId, draftPublicId, true, null));

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    /** 위험도를 모르는 것은 안전하다는 뜻이 아니다(PublishScheduler 와 같은 원칙). */
    @Test
    void 분석_기록이_없으면_승인되지_않는다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");
        when(reviewAnalysisRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.approve(ownerPublicId, draftPublicId, true, null));

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void 남의_매장_초안은_404다() {
        blocked("RISK_LEVEL_TOO_HIGH");
        when(storeRepository.findById(100L))
                .thenReturn(Optional.of(Store.builder().id(100L).ownerId(999L).name("남의 매장").build()));

        ApiException e = assertThrows(ApiException.class,
                () -> service.approve(ownerPublicId, draftPublicId, true, null));

        // ★ 403 이 아니라 404 — 남의 초안이 존재하는지 흘리지 않는다.
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 답글이_280자를_넘으면_거부된다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");

        assertThrows(ApiException.class,
                () -> service.approve(ownerPublicId, draftPublicId, true, "가".repeat(281)));

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void 거절하면_BLOCKED로_남고_게시되지_않는다() {
        ReplyDraft d = blocked("RISK_LEVEL_TOO_HIGH");

        service.reject(ownerPublicId, draftPublicId);

        assertThat(d.getStatus()).isEqualTo("BLOCKED");
        assertThat(d.isHumanApproved()).isFalse();
        assertThat(d.getGuardrailFlags()).contains("HUMAN_REJECTED");
    }
}
