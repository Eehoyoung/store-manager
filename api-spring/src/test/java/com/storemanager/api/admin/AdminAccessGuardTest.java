package com.storemanager.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAccessGuardTest {
    @Mock AppUserRepository users;

    @Test
    void 허용목록에_없는_사용자는_관리자_API에_접근할수없다() {
        UUID id = UUID.randomUUID();
        when(users.findByPublicIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(
                AppUser.builder().publicId(id).email("other@example.com").name("일반사용자").build()));

        ApiException ex = assertThrows(ApiException.class,
                () -> new AdminAccessGuard(users, UUID.randomUUID().toString()).requireAdmin(id));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }
}
