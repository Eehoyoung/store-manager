package com.storemanager.api.agreement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AgreementControllerTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void 소속해제_요청은_HQ철회행과_운영자접수기록을_추가한다() {
        UUID publicId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(publicId.toString(), "", java.util.List.of()));
        AgreementService service = mock(AgreementService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuditLogRepository audits = mock(AuditLogRepository.class);
        when(users.findActiveByPublicIdForUpdate(publicId))
                .thenReturn(Optional.of(AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장")
                        .franchiseBrandName("가맹본부").build()));

        new AgreementController(service, users, audits).requestHqWithdrawal();

        verify(service).record(1L, null, AgreementService.HQ, false, null, null);
        verify(audits).save(any());
    }

    @Test
    void 미소속_또는_이미_접수한_회원은_철회행을_추가하지_않는다() {
        UUID publicId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(publicId.toString(), "", java.util.List.of()));
        AgreementService service = mock(AgreementService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuditLogRepository audits = mock(AuditLogRepository.class);
        AgreementController controller = new AgreementController(service, users, audits);
        when(users.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(
                AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장").build()));
        when(users.findActiveByPublicIdForUpdate(publicId)).thenReturn(Optional.of(
                AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장").build()));

        assertThat(controller.hqWithdrawalStatus().canRequest()).isFalse();
        assertThatThrownBy(controller::requestHqWithdrawal).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(service);
        verify(audits, never()).save(any());

        when(users.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(
                AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장")
                        .franchiseBrandName("가맹본부").build()));
        when(users.findActiveByPublicIdForUpdate(publicId)).thenReturn(Optional.of(
                AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장")
                        .franchiseBrandName("가맹본부").build()));
        when(audits.existsByActionAndActorId("HQ_AFFILIATION_WITHDRAWAL_REQUESTED", 1L)).thenReturn(true);

        assertThat(controller.hqWithdrawalStatus().requested()).isTrue();
        assertThat(controller.hqWithdrawalStatus().canRequest()).isFalse();
        assertThatThrownBy(controller::requestHqWithdrawal).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        verifyNoInteractions(service);
        verify(audits, never()).save(any());
    }
}
