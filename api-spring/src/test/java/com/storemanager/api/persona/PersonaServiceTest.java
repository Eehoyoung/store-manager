package com.storemanager.api.persona;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.ai.AiClient;
import com.storemanager.api.ai.AiClientDtos.AnalysisOut;
import com.storemanager.api.ai.AiClientDtos.AnalyzeAndDraftResponse;
import com.storemanager.api.ai.AiClientDtos.DraftOut;
import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.review.ReplyStyleSample;
import com.storemanager.api.draft.PublishScheduleCalculator;
import com.storemanager.api.persona.PersonaDtos.PersonaRequest;
import com.storemanager.api.persona.PersonaDtos.PersonaResponse;
import com.storemanager.api.persona.PersonaDtos.PreviewRequest;
import com.storemanager.api.persona.PersonaDtos.PreviewResponse;
import com.storemanager.api.persona.PersonaDtos.WindowDto;
import com.storemanager.api.review.UnifiedReview;
import com.storemanager.api.review.UnifiedReviewRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StorePersona;
import com.storemanager.api.store.StorePersonaRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PersonaService 단위테스트(Sprint 5 X2-a, b, c, e, f). AiClient·Repository 는 전부 목으로 대체한다.
 * ★ autoMaxRisk>=3 저장 거부(절대규칙 3)와 미리보기의 risk_level>=3 차단·무저장을 최우선으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PersonaServiceTest {

    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private UnifiedReviewRepository unifiedReviewRepository;
    @Mock private StyleSampleQueryRepository styleSampleQueryRepository;
    @Mock private com.storemanager.api.draft.ReviewAnalysisRepository reviewAnalysisRepository;
    @Mock private AiClient aiClient;
    @Mock private AuditLogRepository auditLogRepository;

    private PersonaService personaService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final UUID storePublicId = UUID.randomUUID();
    private final Store myStore = Store.builder().id(100L).publicId(storePublicId).ownerId(1L).name("가게").build();

    @BeforeEach
    void setUp() {
        personaService = new PersonaService(storePersonaRepository, storeRepository, appUserRepository,
                unifiedReviewRepository, styleSampleQueryRepository, reviewAnalysisRepository, aiClient,
                new ObjectMapper(), auditLogRepository);
        // lenient: bean-validation-only 테스트(autoMaxRisk_* 등)는 personaService 를 호출하지 않아 이 스텁을 안 쓴다.
        org.mockito.Mockito.lenient().when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    private PersonaRequest personaRequest(short lengthMin, short lengthMax, short autoMaxRisk,
            List<WindowDto> windows) {
        return new PersonaRequest("POLITE", true, (short) 1, "고객님", "감사합니다", null, List.of(), lengthMin,
                lengthMax, false, (short) 4, autoMaxRisk, (short) 6, windows);
    }

    // ── X2-a: bean validation ────────────────────────────────────────────

    @Test
    void autoMaxRisk_3과_lengthMax_281은_bean_validation에서_거부된다() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            PersonaRequest req = personaRequest((short) 60, (short) 281, (short) 3, List.of());

            Set<ConstraintViolation<PersonaRequest>> violations = validator.validate(req);

            Set<String> invalidFields = violations.stream().map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.toSet());
            assertThat(invalidFields).contains("autoMaxRisk", "lengthMax");
        }
    }

    @Test
    void autoMaxRisk_2는_bean_validation을_통과한다() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            PersonaRequest req = personaRequest((short) 60, (short) 150, (short) 2, List.of());

            assertThat(validator.validate(req)).isEmpty();
        }
    }

    // ── X2-a: 교차필드(서비스단) 검증 ─────────────────────────────────────

    @Test
    void lengthMin이_lengthMax보다_크면_VALIDATION_FAILED다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L))
                .thenReturn(Optional.of(StorePersona.builder().storeId(100L).personaSeed(1).build()));
        PersonaRequest req = personaRequest((short) 200, (short) 150, (short) 1, List.of());

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.updatePersona(ownerPublicId, storePublicId, req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ── X2-b: publishWindows 형식/순서 검증 + 정상값 파싱 ────────────────

    @Test
    void publishWindows_start가_end보다_늦으면_VALIDATION_FAILED다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L))
                .thenReturn(Optional.of(StorePersona.builder().storeId(100L).personaSeed(1).build()));
        PersonaRequest req = personaRequest((short) 60, (short) 150, (short) 1,
                List.of(new WindowDto("11:00", "10:00")));

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.updatePersona(ownerPublicId, storePublicId, req));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 저장된_publishWindows는_PublishScheduleCalculator로_실제_파싱된다() throws Exception {
        StorePersona persona = StorePersona.builder().storeId(100L).personaSeed(1).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        PersonaRequest req = personaRequest((short) 60, (short) 150, (short) 1,
                List.of(new WindowDto("10:00", "11:30"), new WindowDto("15:00", "16:00")));

        PersonaResponse res = personaService.updatePersona(ownerPublicId, storePublicId, req);
        assertThat(res.publishWindows()).hasSize(2);

        // ReplyDraft/PublishScheduler 가 실제로 파싱하는 것과 동일한 방식으로 재파싱해본다(DraftService.parseWindows 규약).
        ObjectMapper om = new ObjectMapper();
        List<Map<String, String>> raw = om.readValue(persona.getPublishWindows(),
                new TypeReference<List<Map<String, String>>>() {
                });
        List<PublishScheduleCalculator.Window> windows = raw.stream()
                .map(w -> new PublishScheduleCalculator.Window(LocalTime.parse(w.get("start")),
                        LocalTime.parse(w.get("end"))))
                .toList();

        Instant result = PublishScheduleCalculator.compute(Instant.now(), 0, windows);
        assertThat(result).isNotNull();
    }

    // ── X2-c: 소유권 ───────────────────────────────────────────────────

    @Test
    void 남의_매장_페르소나_조회는_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.getPersona(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── X2-e, f: 미리보기 ─────────────────────────────────────────────

    @Test
    void risk_level_3이면_미리보기는_422이고_아무것도_저장하지_않는다() {
        StorePersona persona = StorePersona.builder().storeId(100L).tone("POLITE").personaSeed(1)
                .lengthMin((short) 60).lengthMax((short) 150).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        UnifiedReview review = UnifiedReview.builder().id(30L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-30").rating((short) 1).body("이물질이 나왔어요").build();
        when(unifiedReviewRepository.findById(30L)).thenReturn(Optional.of(review));
        AnalysisOut analysisOut = new AnalysisOut("COMPLAINT", -0.9f, List.of("이물질"), 3, List.of("FOREIGN_OBJECT"),
                "m", "v1");
        when(aiClient.analyzeAndDraft(any()))
                .thenReturn(new AnalyzeAndDraftResponse(analysisOut, List.of(), false, List.of()));

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.preview(ownerPublicId, storePublicId, new PreviewRequest("30", null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RISK_LEVEL_TOO_HIGH);
        verify(storePersonaRepository, never()).save(any());
    }

    @Test
    void 정상_미리보기는_내용을_반환하고_reply_draft를_저장하지_않는다() {
        StorePersona persona = StorePersona.builder().storeId(100L).tone("FRIENDLY").personaSeed(1)
                .lengthMin((short) 60).lengthMax((short) 150).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        UnifiedReview review = UnifiedReview.builder().id(31L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-31").rating((short) 5).body("맛있어요").build();
        when(unifiedReviewRepository.findById(31L)).thenReturn(Optional.of(review));
        AnalysisOut analysisOut = new AnalysisOut("PRAISE", 0.9f, List.of(), 0, List.of(), "local-7b", "v1");
        DraftOut draftOut = new DraftOut("고객님, 감사합니다 :)", "T1", "local-7b", "v1", List.of(), 0.2f, 100, 40, 0.5);
        when(aiClient.analyzeAndDraft(any()))
                .thenReturn(new AnalyzeAndDraftResponse(analysisOut, List.of(draftOut), false, List.of()));

        PreviewResponse res = personaService.preview(ownerPublicId, storePublicId, new PreviewRequest("31", null));

        assertThat(res.content()).isEqualTo("고객님, 감사합니다 :)");
        // PersonaService 는 ReplyDraftRepository 자체를 의존하지 않는다 — 구조적으로 reply_draft 를 쓸 수 없다.
        // 대신 유일하게 쓰기가 가능한 저장소(storePersonaRepository)에 save 호출이 없었음을 확인한다.
        verify(storePersonaRepository, never()).save(any());
    }

    @Test
    void 저장된_분석이_risk_3이면_AI를_호출하지도_않고_422다() {
        // ★ 분류는 비결정적이다. 이번 호출에서 AI 가 risk 0 을 돌려주더라도
        // 이미 risk_level=3 으로 확정된 리뷰는 미리보기를 만들면 안 된다(절대규칙 3).
        // AI 응답만 보고 판정하면 fail-open 이 된다.
        StorePersona persona = StorePersona.builder().storeId(100L).tone("POLITE").personaSeed(1)
                .lengthMin((short) 60).lengthMax((short) 150).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(storePersonaRepository.findById(100L)).thenReturn(Optional.of(persona));
        UnifiedReview review = UnifiedReview.builder().id(32L).storeId(100L).linkId(1L).platform("BAEMIN")
                .platformReviewId("r-32").rating((short) 1).body("머리카락이 나왔습니다").build();
        when(unifiedReviewRepository.findById(32L)).thenReturn(Optional.of(review));
        when(reviewAnalysisRepository.findById(32L)).thenReturn(Optional.of(
                com.storemanager.api.draft.ReviewAnalysis.builder().reviewId(32L).category("COMPLAINT")
                        .sentiment(-0.9f).riskLevel((short) 3).riskReasons(new String[] {"FOREIGN_OBJECT"})
                        .model("m").promptVersion("v1").build()));

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.preview(ownerPublicId, storePublicId, new PreviewRequest("32", null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RISK_LEVEL_TOO_HIGH);
        // 차단 대상에 LLM 비용을 쓰지 않는다.
        verify(aiClient, never()).analyzeAndDraft(any());
    }

    // ── B4: 스타일샘플 삭제 ────────────────────────────────────────────

    @Test
    void 남의_매장_스타일샘플_삭제는_404이고_삭제되지_않는다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        ReplyStyleSample sample = ReplyStyleSample.builder().id(40L).storeId(999L) // 남의 매장
                .reviewText("리뷰").replyText("답글").build();
        when(styleSampleQueryRepository.findById(40L)).thenReturn(Optional.of(sample));

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.deleteStyleSample(ownerPublicId, storePublicId, 40L));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verify(styleSampleQueryRepository, never()).delete(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_스타일샘플_삭제는_404다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        when(styleSampleQueryRepository.findById(41L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> personaService.deleteStyleSample(ownerPublicId, storePublicId, 41L));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 내_매장_스타일샘플_삭제는_삭제하고_감사로그를_남긴다() {
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(myStore));
        ReplyStyleSample sample = ReplyStyleSample.builder().id(42L).storeId(100L)
                .reviewText("리뷰").replyText("답글").build();
        when(styleSampleQueryRepository.findById(42L)).thenReturn(Optional.of(sample));

        personaService.deleteStyleSample(ownerPublicId, storePublicId, 42L);

        verify(styleSampleQueryRepository).delete(sample);
        verify(auditLogRepository).save(org.mockito.ArgumentMatchers.argThat((AuditLog a) ->
                "STYLE_SAMPLE_DELETED".equals(a.getAction()) && "REPLY_STYLE_SAMPLE".equals(a.getTargetType())
                        && Long.valueOf(42L).equals(a.getTargetId())));
    }
}
