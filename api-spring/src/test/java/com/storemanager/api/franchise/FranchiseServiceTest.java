package com.storemanager.api.franchise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storemanager.api.agreement.UserAgreementRepository;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
import com.storemanager.api.sysauth.MailService;
import com.storemanager.api.sysauth.SessionService;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.Store;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FranchiseServiceTest {

    @Mock FranchiseJoinCodeRepository joinCodeRepository;
    @Mock FranchiseBrandRepository franchiseBrandRepository;
    @Mock FranchiseHqMemberRepository hqMemberRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock FranchiseAffiliationRequestRepository affiliationRepository;
    @Mock StoreRepository storeRepository;
    @Mock UserAgreementRepository userAgreementRepository;
    @Mock MailService mailService;
    @Mock SessionService sessionService;

    private FranchiseService service;

    @BeforeEach
    void setUp() {
        service = new FranchiseService(joinCodeRepository, franchiseBrandRepository, hqMemberRepository,
                appUserRepository, auditLogRepository, affiliationRepository, storeRepository,
                userAgreementRepository, mailService, sessionService, new ObjectMapper());
    }

    @Test
    void 본부를_생성하면_브랜드와_담당자와_가맹코드를_만든다() {
        var req = new FranchiseDtos.CreateFranchiseRequest("소담치킨", "본부담당자", "hq@sodam.test", "매니저", true,
                "신규 가맹본부 온보딩");
        when(franchiseBrandRepository.findByBrandName("소담치킨")).thenReturn(Optional.empty());
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("hq@sodam.test")).thenReturn(Optional.empty());
        AppUser saved = AppUser.builder().id(7L).email("hq@sodam.test").name("본부담당자").build();
        when(appUserRepository.save(any(AppUser.class))).thenReturn(saved);
        when(hqMemberRepository.save(any(FranchiseHqMember.class)))
                .thenReturn(FranchiseHqMember.builder().id(1L).userId(7L).brandName("소담치킨").build());

        var response = service.createFranchise(req, "adminref1234");

        assertThat(response.brandName()).isEqualTo("소담치킨");
        assertThat(response.joinCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        verify(franchiseBrandRepository).save(any(FranchiseBrand.class));
        verify(mailService).sendHqInvite("hq@sodam.test", "소담치킨");
        verify(auditLogRepository).save(any());
    }

    @Test
    void 이미_있는_브랜드명은_거절한다() {
        when(franchiseBrandRepository.findByBrandName("소담치킨"))
                .thenReturn(Optional.of(FranchiseBrand.builder().brandName("소담치킨").build()));

        var req = new FranchiseDtos.CreateFranchiseRequest("소담치킨", "담당자", "a@b.com", null, false, "사유");
        ApiException ex = assertThrows(ApiException.class, () -> service.createFranchise(req, "ref"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void 가맹점_소속신청은_활성코드일때만_받는다() {
        FranchiseJoinCode code = FranchiseJoinCode.builder().id(1L).brandName("소담치킨")
                .codeHash("x".repeat(64)).build();
        when(joinCodeRepository.findByCodeHashAndActiveTrue(any())).thenReturn(Optional.of(code));

        AppUser user = AppUser.builder().id(1L).email("a@b.com").name("신청자").build();
        Store store = Store.builder().id(1L).ownerId(1L).name("매장").build();
        service.requestAffiliation(user, store, "any-code");

        ArgumentCaptor<FranchiseAffiliationRequest> captor = ArgumentCaptor.forClass(FranchiseAffiliationRequest.class);
        verify(affiliationRepository).save(captor.capture());
        assertThat(captor.getValue().getJoinCodeId()).isEqualTo(1L);
    }

    @Test
    void 등록되지_않은_가맹코드는_거절한다() {
        when(joinCodeRepository.findByCodeHashAndActiveTrue(any())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.requestAffiliation(
                AppUser.builder().id(1L).email("a@b.com").name("신청자").build(),
                Store.builder().id(1L).ownerId(1L).name("매장").build(), "wrong-code"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_FRANCHISE_CODE);
    }

    // ── 가맹 브랜드 구간 단가(V49) — 약정 매장 수 ─────────────────────────────

    @Test
    void 약정_매장수를_설정한다() {
        FranchiseBrand brand = FranchiseBrand.builder().brandName("소담치킨").build();
        when(franchiseBrandRepository.findByBrandName("소담치킨")).thenReturn(Optional.of(brand));

        service.setCommittedStoreCount("소담치킨", 50, "계약 체결", "admin-ref");

        assertThat(brand.getCommittedStoreCount()).isEqualTo(50);
        verify(auditLogRepository).save(any());
    }

    @Test
    void 약정_매장수를_null로_해제할_수_있다() {
        FranchiseBrand brand = FranchiseBrand.builder().brandName("소담치킨").committedStoreCount(50).build();
        when(franchiseBrandRepository.findByBrandName("소담치킨")).thenReturn(Optional.of(brand));

        service.setCommittedStoreCount("소담치킨", null, "약정 해제", "admin-ref");

        assertThat(brand.getCommittedStoreCount()).isNull();
    }

    @Test
    void 약정_매장수는_1미만이거나_100000_초과면_거절한다() {
        FranchiseBrand brand = FranchiseBrand.builder().brandName("소담치킨").build();
        when(franchiseBrandRepository.findByBrandName("소담치킨")).thenReturn(Optional.of(brand));

        ApiException tooLow = assertThrows(ApiException.class,
                () -> service.setCommittedStoreCount("소담치킨", 0, "사유", "admin-ref"));
        assertThat(tooLow.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        ApiException tooHigh = assertThrows(ApiException.class,
                () -> service.setCommittedStoreCount("소담치킨", 100_001, "사유", "admin-ref"));
        assertThat(tooHigh.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(brand.getCommittedStoreCount()).isNull(); // 거절됐으니 바뀌지 않는다
    }

    @Test
    void 존재하지_않는_브랜드의_약정_매장수는_설정할_수_없다() {
        when(franchiseBrandRepository.findByBrandName("없는브랜드")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.setCommittedStoreCount("없는브랜드", 50, "사유", "admin-ref"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
