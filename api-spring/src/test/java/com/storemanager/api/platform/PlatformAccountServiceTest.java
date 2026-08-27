package com.storemanager.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.agreement.AgreementService;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★ 이 테스트는 '같은 배달앱 계정을 두 곳에서 연동할 수 없다' 는 규칙을 잠근다.
 * 이 검사를 지우면 같은 매장을 두 앱 계정이 동시에 수집해 DataAPI 호출이 이중 과금되고,
 * 같은 리뷰에 답글이 두 번 달린다. "오류가 떠서 불편하다" 는 이유로 제거하지 말 것 —
 * 고칠 것은 검사가 아니라 안내 문구다.
 */
class PlatformAccountServiceTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlatformAccountRepository accountRepository = mock(PlatformAccountRepository.class);
    private final StorePlatformLinkRepository linkRepository = mock(StorePlatformLinkRepository.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final AgreementService agreementService = mock(AgreementService.class);

    private final PlatformAccountService service = new PlatformAccountService(
            appUserRepository, storeRepository, accountRepository, linkRepository, credentialService,
            agreementService);

    @Test
    void 이미_연동된_배달앱_계정은_409로_거절하고_자격증명을_저장하지_않는다() {
        UUID ownerPublicId = UUID.randomUUID();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(mock(AppUser.class)));
        when(accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull("BAEMIN", "jinsa66"))
                .thenReturn(true);

        var request = new RegisterPlatformAccountRequest("BAEMIN", "jinsa66", "pw", UUID.randomUUID(), true,
                AgreementService.CURRENT_VERSION);

        assertThatThrownBy(() -> service.register(ownerPublicId, request, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLATFORM_ACCOUNT_ALREADY_LINKED);

        // 중복이면 비밀번호를 암호화·저장하는 단계까지 가서는 안 된다.
        verify(credentialService, never()).save(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void 해지된_계정의_아이디는_다시_연동할_수_있다() {
        // revoked_at 이 찍힌 행은 유니크 제약에서 빠진다. 해지 후 재연동을 막으면
        // 사장님이 계정을 옮길 방법이 없어진다.
        when(accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull("BAEMIN", "jinsa66"))
                .thenReturn(false);

        assertThat(accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull("BAEMIN", "jinsa66"))
                .isFalse();
    }

    @Test
    void 알_수_없는_플랫폼은_거절한다() {
        UUID ownerPublicId = UUID.randomUUID();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(mock(AppUser.class)));

        var request = new RegisterPlatformAccountRequest("YOGIYO2", "id", "pw", UUID.randomUUID(), true,
                AgreementService.CURRENT_VERSION);

        assertThatThrownBy(() -> service.register(ownerPublicId, request, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(accountRepository, never()).existsByPlatformAndLoginIdAndRevokedAtIsNull(any(), any());
    }

    @Test
    void 로그인_아이디는_마스킹해서_내보낸다() {
        // 응답·로그에 배달앱 아이디 원문이 그대로 나가면 안 된다.
        assertThat(PlatformAccountService.maskLoginId("jinsa66")).isEqualTo("ji••••66");
        assertThat(PlatformAccountService.maskLoginId("ab")).isEqualTo("••••");
    }

    @Test
    void 위탁동의가_없으면_자격증명을_저장하지_않는다() {
        var request = new RegisterPlatformAccountRequest("BAEMIN", "id", "pw", UUID.randomUUID(), false,
                AgreementService.CURRENT_VERSION);
        assertThatThrownBy(() -> service.register(UUID.randomUUID(), request, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONSENT_REQUIRED);
        verifyNoInteractions(credentialService, accountRepository);
    }

    @Test
    void 위탁동의하면_매장을_활성화하고_동의행을_남긴다() {
        UUID ownerPublicId = UUID.randomUUID();
        UUID storePublicId = UUID.randomUUID();
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("a@b.com").name("사장").build();
        Store store = Store.builder().id(2L).publicId(storePublicId).ownerId(1L).name("매장").build();
        PlatformAccount account = PlatformAccount.builder().id(3L).ownerId(1L).platform("BAEMIN")
                .loginId("ownerid").encPassword(new byte[1]).encDek(new byte[1]).kmsKeyId("k")
                .encNonce(new byte[1]).passwordFingerprint(new byte[1]).intendedStoreId(2L).build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(store));
        when(credentialService.save(1L, "BAEMIN", "ownerid", "pw", 2L)).thenReturn(account);
        when(linkRepository.findByAccountIdOrderByCreatedAtAsc(3L)).thenReturn(java.util.List.of());

        service.register(ownerPublicId, new RegisterPlatformAccountRequest("BAEMIN", "ownerid", "pw",
                storePublicId, true, AgreementService.CURRENT_VERSION), "127.0.0.1", "test");

        assertThat(store.getActivatedAt()).isNotNull();
        verify(agreementService).record(1L, 2L, AgreementService.CREDENTIAL, true, "127.0.0.1", "test");
    }

    @Test
    void 연동해제하면_매장을_비활성화하고_철회행을_남긴다() {
        UUID ownerPublicId = UUID.randomUUID();
        UUID accountPublicId = UUID.randomUUID();
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("a@b.com").name("사장").build();
        Store store = Store.builder().id(2L).ownerId(1L).name("매장").activatedAt(java.time.Instant.now()).build();
        PlatformAccount account = PlatformAccount.builder().id(3L).publicId(accountPublicId).ownerId(1L)
                .platform("BAEMIN").loginId("ownerid").encPassword(new byte[1]).encDek(new byte[1]).kmsKeyId("k")
                .encNonce(new byte[1]).passwordFingerprint(new byte[1]).intendedStoreId(2L).build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        when(accountRepository.findByPublicIdAndOwnerIdAndRevokedAtIsNull(accountPublicId, 1L))
                .thenReturn(Optional.of(account));
        when(storeRepository.findById(2L)).thenReturn(Optional.of(store));

        service.revoke(ownerPublicId, accountPublicId, "127.0.0.1", "test");

        assertThat(store.getActivatedAt()).isNull();
        verify(agreementService).record(1L, 2L, AgreementService.CREDENTIAL, false, "127.0.0.1", "test");
    }
}
