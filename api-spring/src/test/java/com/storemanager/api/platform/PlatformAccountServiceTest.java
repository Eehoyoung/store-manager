package com.storemanager.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.review.StorePlatformLinkRepository;
import com.storemanager.api.store.StoreRepository;
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

    private final PlatformAccountService service = new PlatformAccountService(
            appUserRepository, storeRepository, accountRepository, linkRepository, credentialService);

    @Test
    void 이미_연동된_배달앱_계정은_409로_거절하고_자격증명을_저장하지_않는다() {
        UUID ownerPublicId = UUID.randomUUID();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(mock(AppUser.class)));
        when(accountRepository.existsByPlatformAndLoginIdAndRevokedAtIsNull("BAEMIN", "jinsa66"))
                .thenReturn(true);

        var request = new RegisterPlatformAccountRequest("BAEMIN", "jinsa66", "pw", UUID.randomUUID());

        assertThatThrownBy(() -> service.register(ownerPublicId, request))
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

        var request = new RegisterPlatformAccountRequest("YOGIYO2", "id", "pw", UUID.randomUUID());

        assertThatThrownBy(() -> service.register(ownerPublicId, request))
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
}
