package com.storemanager.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StoreService 단위테스트(Sprint 5 B5, B6-d). 남의 매장 접근이 403 이 아니라 404 임을 검증한다
 * — review/persona/analytics 와의 응답코드 일관성(오케스트레이터 지시).
 */
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock private StoreRepository storeRepository;
    @Mock private StorePersonaRepository storePersonaRepository;
    @Mock private AppUserRepository appUserRepository;

    private StoreService storeService;

    private final UUID ownerPublicId = UUID.randomUUID();
    private final AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("owner@store.com")
            .name("사장").build();
    private final UUID storePublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storeService = new StoreService(storeRepository, storePersonaRepository, appUserRepository);
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
    }

    @Test
    void 남의_매장_조회는_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class, () -> storeService.getStore(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 남의_매장_수정은_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> storeService.updateStore(ownerPublicId, storePublicId,
                        new UpdateStoreRequest("새이름", null, null, null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 남의_매장_삭제는_404다() {
        Store othersStore = Store.builder().id(999L).publicId(storePublicId).ownerId(2L).name("남의가게").build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId)).thenReturn(Optional.of(othersStore));

        ApiException ex = assertThrows(ApiException.class,
                () -> storeService.deleteStore(ownerPublicId, storePublicId));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
