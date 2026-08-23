package com.storemanager.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.billing.Subscription;
import com.storemanager.api.billing.SubscriptionRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.crypto.CredentialService;
import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.crypto.PlatformAccountRepository;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회원 탈퇴는 되돌릴 수 없다. 빠뜨린 단계마다 다른 사고가 되므로 전부 잠근다.
 *
 * <p>이 테스트가 깨졌다는 것은 탈퇴 처리에서 무언가 빠졌다는 뜻이다. 테스트를 고치기 전에
 * 서비스가 맞는지 먼저 보라.
 */
class AccountWithdrawalServiceTest {

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final PlatformAccountRepository platformAccountRepository = mock(PlatformAccountRepository.class);
    private final CredentialService credentialService = mock(CredentialService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final AccountWithdrawalService service = new AccountWithdrawalService(
            appUserRepository, storeRepository, subscriptionRepository, platformAccountRepository,
            credentialService, auditLogRepository, passwordEncoder);

    private final UUID publicId = UUID.randomUUID();

    private AppUser user() {
        return AppUser.builder().id(1L).publicId(publicId).email("owner@example.com")
                .name("사장").passwordHash("hashed").build();
    }

    private void given(AppUser user, List<Store> stores, List<PlatformAccount> accounts, boolean pwOk) {
        when(appUserRepository.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(pwOk);
        when(storeRepository.findByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(stores);
        when(platformAccountRepository.findByOwnerIdAndRevokedAtIsNullOrderByCreatedAtDesc(1L)).thenReturn(accounts);
    }

    private Store store() {
        return Store.builder().id(10L).ownerId(1L).name("가게").status("ACTIVE")
                .activatedAt(Instant.now()).build();
    }

    @Test
    void 비밀번호가_틀리면_탈퇴하지_않는다() {
        // 세션이 탈취된 상태에서 계정이 삭제되면 복구할 방법이 없다.
        given(user(), List.of(), List.of(), false);
        assertThatThrownBy(() -> service.withdraw(publicId, "wrong")).isInstanceOf(ApiException.class);
        verify(credentialService, never()).revoke(any());
    }

    @Test
    void 배달앱_자격증명을_파기한다() {
        // 남겨두면 탈퇴한 사람의 배달앱 계정 암호문이 그대로 보관된다. 가장 위험하다.
        PlatformAccount a1 = mock(PlatformAccount.class);
        PlatformAccount a2 = mock(PlatformAccount.class);
        given(user(), List.of(), List.of(a1, a2), true);
        service.withdraw(publicId, "pw");
        verify(credentialService, times(2)).revoke(any());
    }

    @Test
    void 구독을_즉시_해지한다() {
        // 안 하면 탈퇴한 사람에게 요금이 청구된다.
        Subscription sub = Subscription.builder().storeId(10L).status("ACTIVE")
                .priceKrw(new BigDecimal("30000")).build();
        when(subscriptionRepository.findByStoreIdAndStatusNot(10L, "CANCELED")).thenReturn(Optional.of(sub));
        given(user(), List.of(store()), List.of(), true);

        service.withdraw(publicId, "pw");

        assertThat(sub.getStatus()).isEqualTo("CANCELED");
        assertThat(sub.getCanceledAt()).isNotNull();
    }

    @Test
    void 매장의_activated_at_을_비운다() {
        // ★ 이게 남아 있으면 수집·생성·게시가 계속 돌아 탈퇴한 사람의 매장에 답글이 달린다.
        Store store = store();
        when(subscriptionRepository.findByStoreIdAndStatusNot(anyLong(), anyString())).thenReturn(Optional.empty());
        given(user(), List.of(store), List.of(), true);

        service.withdraw(publicId, "pw");

        assertThat(store.getActivatedAt()).isNull();
        assertThat(store.getDeletedAt()).isNotNull();
        assertThat(store.getStatus()).isEqualTo("DELETED");
    }

    @Test
    void 이메일을_풀어_재가입을_막지_않는다() {
        // deleted_at 을 세워야 uq_user_email(WHERE deleted_at IS NULL) 이 풀린다.
        // status 만 바꾸면 그 이메일이 영구히 잠긴다.
        AppUser user = user();
        given(user, List.of(), List.of(), true);

        service.withdraw(publicId, "pw");

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getStatus()).isEqualTo("WITHDRAWN");
        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    void 탈퇴_사실을_감사로그에_남긴다() {
        // '언제 누가 탈퇴했는가' 는 개인정보가 아니라 처리 이력이며, 분쟁 시 우리를 지킨다.
        given(user(), List.of(), List.of(), true);
        service.withdraw(publicId, "pw");
        verify(auditLogRepository).save(any());
    }
}
