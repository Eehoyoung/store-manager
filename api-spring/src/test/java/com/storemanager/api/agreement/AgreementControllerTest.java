package com.storemanager.api.agreement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storemanager.api.audit.AuditLogRepository;
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
        when(users.findByPublicIdAndDeletedAtIsNull(publicId))
                .thenReturn(Optional.of(AppUser.builder().id(1L).publicId(publicId).email("a@b.com").name("사장").build()));

        new AgreementController(service, users, audits).requestHqWithdrawal();

        verify(service).record(1L, null, AgreementService.HQ, false, null, null);
        verify(audits).save(any());
    }
}
