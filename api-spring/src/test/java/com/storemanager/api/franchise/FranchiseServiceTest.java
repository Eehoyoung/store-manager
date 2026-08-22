package com.storemanager.api.franchise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseDtos.ProvisionRequest;
import com.storemanager.api.franchise.FranchiseDtos.ProvisionResponse;
import com.storemanager.api.hq.FranchiseHqMember;
import com.storemanager.api.hq.FranchiseHqMemberRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class FranchiseServiceTest {

    @Mock FranchiseJoinCodeRepository joinCodeRepository;
    @Mock FranchiseHqMemberRepository hqMemberRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock FranchiseAffiliationRequestRepository affiliationRepository;
    @Mock StoreRepository storeRepository;

    private FranchiseService service;

    @BeforeEach
    void setUp() {
        service = new FranchiseService(joinCodeRepository, hqMemberRepository, appUserRepository,
                auditLogRepository, passwordEncoder, affiliationRepository, storeRepository);
    }

    @Test
    void 본부계정과_가맹코드를_발급하고_코드원문은_저장하지_않는다() {
        ProvisionRequest req = new ProvisionRequest("소담치킨", "hq@sodam.test", "password1234", "본부담당자", null);
        AppUser hqUser = AppUser.builder().id(7L).email(req.hqEmail()).name(req.hqName()).build();
        when(appUserRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(req.hqEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(req.hqPassword())).thenReturn("bcrypt");
        when(appUserRepository.save(any(AppUser.class))).thenReturn(hqUser);

        ProvisionResponse response = service.provision(req);

        ArgumentCaptor<FranchiseJoinCode> codeCaptor = ArgumentCaptor.forClass(FranchiseJoinCode.class);
        ArgumentCaptor<FranchiseHqMember> memberCaptor = ArgumentCaptor.forClass(FranchiseHqMember.class);
        verify(joinCodeRepository).save(codeCaptor.capture());
        verify(hqMemberRepository).save(memberCaptor.capture());
        FranchiseJoinCode stored = codeCaptor.getValue();
        assertThat(memberCaptor.getValue().getBrandName()).isEqualTo(stored.getBrandName());
        assertThat(response.franchiseCode()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(stored.getCodeHash()).hasSize(64).isNotEqualTo(response.franchiseCode());
        when(joinCodeRepository.findByCodeHashAndActiveTrue(stored.getCodeHash())).thenReturn(Optional.of(stored));
        service.requestAffiliation(hqUser, Store.builder().id(8L).ownerId(7L).name("가맹점").build(),
                response.franchiseCode().toLowerCase());
        verify(affiliationRepository).save(any(FranchiseAffiliationRequest.class));
    }

    @Test
    void 등록되지_않은_가맹코드는_거절한다() {
        when(joinCodeRepository.findByCodeHashAndActiveTrue(any())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.requestAffiliation(
                AppUser.builder().id(1L).email("a@b.com").name("신청자").build(),
                Store.builder().id(1L).ownerId(1L).name("매장").build(), "wrong-code"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_FRANCHISE_CODE);
    }
}
