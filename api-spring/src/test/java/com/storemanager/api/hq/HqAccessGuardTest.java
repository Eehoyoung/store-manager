package com.storemanager.api.hq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.franchise.FranchiseBrand;
import com.storemanager.api.franchise.FranchiseBrandRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** docs/26a — 멤버 REVOKED·브랜드 SUSPENDED 는 요청마다 404 로 막힌다(HqAccessGuard.requireBrandAccess). */
class HqAccessGuardTest {

    private FranchiseHqMemberRepository hqMemberRepository;
    private FranchiseBrandRepository franchiseBrandRepository;
    private AppUserRepository appUserRepository;
    private HqAccessGuard guard;

    private final UUID userPublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        hqMemberRepository = mock(FranchiseHqMemberRepository.class);
        franchiseBrandRepository = mock(FranchiseBrandRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        guard = new HqAccessGuard(hqMemberRepository, franchiseBrandRepository, mock(StoreRepository.class),
                appUserRepository);
        when(appUserRepository.findByPublicId(userPublicId))
                .thenReturn(Optional.of(AppUser.builder().id(1L).publicId(userPublicId).email("hq@x.com")
                        .name("담당자").build()));
    }

    @Test
    void 활성_멤버_활성_브랜드는_통과한다() {
        when(hqMemberRepository.findByUserIdAndBrandName(1L, "브랜드A")).thenReturn(
                Optional.of(FranchiseHqMember.builder().userId(1L).brandName("브랜드A").build()));
        when(franchiseBrandRepository.findByBrandName("브랜드A"))
                .thenReturn(Optional.of(FranchiseBrand.builder().brandName("브랜드A").status("ACTIVE").build()));

        assertThat(guard.requireBrandAccess(userPublicId, "브랜드A").getId()).isEqualTo(1L);
    }

    @Test
    void REVOKED_멤버는_404다() {
        FranchiseHqMember revoked = FranchiseHqMember.builder().userId(1L).brandName("브랜드A").build();
        revoked.revoke("adminref", java.time.Instant.now());
        when(hqMemberRepository.findByUserIdAndBrandName(1L, "브랜드A")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> guard.requireBrandAccess(userPublicId, "브랜드A"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void SUSPENDED_브랜드는_활성_멤버여도_404다() {
        when(hqMemberRepository.findByUserIdAndBrandName(1L, "브랜드A")).thenReturn(
                Optional.of(FranchiseHqMember.builder().userId(1L).brandName("브랜드A").build()));
        when(franchiseBrandRepository.findByBrandName("브랜드A"))
                .thenReturn(Optional.of(FranchiseBrand.builder().brandName("브랜드A").status("SUSPENDED").build()));

        assertThatThrownBy(() -> guard.requireBrandAccess(userPublicId, "브랜드A"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 멤버십_자체가_없으면_404다() {
        when(hqMemberRepository.findByUserIdAndBrandName(1L, "브랜드A")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireBrandAccess(userPublicId, "브랜드A"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
