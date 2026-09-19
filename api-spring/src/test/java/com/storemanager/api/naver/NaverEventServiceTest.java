package com.storemanager.api.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.naver.NaverDtos.BulkApproveResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 네이버 일괄승인 단위테스트(docs/naver/03-compliance.md "Tier 1 성립 조건").
 * ★ 이 파일이 지키는 것 — (1) VIEWED 를 거치지 않은 항목은 절대 일괄승인되지 않는다
 * (2) 1~2점은 무조건 제외된다 (3) PIN 없이는 아무것도 승인되지 않는다(fail-closed).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NaverEventServiceTest {

    @Mock private NaverReviewEventRepository naverReviewEventRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private NaverEventService service;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final UUID storePublicId = UUID.randomUUID();
    private final Store store = Store.builder().id(100L).publicId(storePublicId).ownerId(1L).name("가게").build();

    @BeforeEach
    void setUp() {
        service = new NaverEventService(naverReviewEventRepository, storeRepository, appUserRepository,
                auditLogRepository, passwordEncoder);
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(store));
    }

    private NaverReviewEvent event(String hash, String status, Integer rating, boolean blocked, int riskLevel) {
        return NaverReviewEvent.builder().id((long) hash.hashCode()).storeId(100L).reviewHash(hash)
                .status(status).rating(rating == null ? null : rating.shortValue()).blocked(blocked)
                .riskLevel((short) riskLevel).build();
    }

    @Test
    void bulkApprove가_PIN_미설정_시_전량_거부한다() {
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("o@t.com").name("사장")
                .naverBulkPinHash(null).build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkApprove(ownerPublicId, storePublicId.toString(), List.of("h1"), "1234"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(naverReviewEventRepository, never()).findByStoreIdAndReviewHashIn(any(), any());
    }

    @Test
    void bulkApprove가_틀린_PIN을_거부한다() {
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("o@t.com").name("사장")
                .naverBulkPinHash("encoded").build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkApprove(ownerPublicId, storePublicId.toString(), List.of("h1"), "0000"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void bulkApprove가_VIEWED_아닌_건과_저평점을_제외하고_나머지만_승인한다() {
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("o@t.com").name("사장")
                .naverBulkPinHash("encoded").build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        NaverReviewEvent notViewed = event("h-drafted", "DRAFTED", 5, false, 0);
        NaverReviewEvent lowRating = event("h-low", "VIEWED", 2, false, 0);
        NaverReviewEvent risky = event("h-risk", "VIEWED", 5, false, 2);
        NaverReviewEvent blocked = event("h-blocked", "VIEWED", 5, true, 0);
        NaverReviewEvent eligible = event("h-ok", "VIEWED", 5, false, 0);
        List<String> hashes = List.of("h-drafted", "h-low", "h-risk", "h-blocked", "h-ok", "h-missing");
        when(naverReviewEventRepository.findByStoreIdAndReviewHashIn(100L, hashes))
                .thenReturn(List.of(notViewed, lowRating, risky, blocked, eligible));

        BulkApproveResponse res = service.bulkApprove(ownerPublicId, storePublicId.toString(), hashes, "1234");

        assertThat(res.approved()).containsExactly("h-ok");
        assertThat(eligible.getStatus()).isEqualTo("APPROVED");
        assertThat(res.excluded()).containsEntry("h-drafted", "NOT_VIEWED");
        assertThat(res.excluded()).containsEntry("h-low", "LOW_RATING");
        assertThat(res.excluded()).containsEntry("h-risk", "RISK_BLOCKED");
        assertThat(res.excluded()).containsEntry("h-blocked", "RISK_BLOCKED");
        assertThat(res.excluded()).containsEntry("h-missing", "NOT_FOUND");
    }

    @Test
    void 다른_사장님_매장_storeId로_호출하면_404를_반환한다() {
        AppUser stranger = AppUser.builder().id(999L).publicId(ownerPublicId).email("s@t.com").name("타인").build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(stranger));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.bulkApprove(ownerPublicId, storePublicId.toString(), List.of("h1"), "1234"));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
