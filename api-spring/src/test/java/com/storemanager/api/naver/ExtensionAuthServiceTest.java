package com.storemanager.api.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.storemanager.api.naver.NaverDtos.PairResponse;
import com.storemanager.api.store.Store;
import com.storemanager.api.store.StoreRepository;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Duration;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ExtensionAuthService.pair() 단위테스트 — PairResponse 에 소유 매장이 public_id 로만
 * 담기는지 검증한다(IMPLEMENTATION_PLAN_NAVER.md §5 T1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtensionAuthServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private AppUserRepository appUserRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private ExtensionAuthService service;

    private final UUID ownerPublicId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new ExtensionAuthService(redisTemplate, appUserRepository, storeRepository, passwordEncoder);
    }

    @Test
    void pair는_소유_매장을_public_id로만_돌려준다() {
        when(valueOperations.get("extpair:ABCDEFGH")).thenReturn(ownerPublicId.toString());
        AppUser owner = AppUser.builder().id(1L).publicId(ownerPublicId).email("o@t.com").name("사장").build();
        when(appUserRepository.findByPublicId(ownerPublicId)).thenReturn(Optional.of(owner));
        UUID storePublicId = UUID.randomUUID();
        Store store = Store.builder().id(999L).publicId(storePublicId).ownerId(1L).name("가게").build();
        when(storeRepository.findByOwnerIdAndDeletedAtIsNull(1L)).thenReturn(List.of(store));

        PairResponse res = service.pair("abcdefgh");

        assertThat(res.token()).isNotBlank();
        assertThat(res.expiresInSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());
        assertThat(res.stores()).hasSize(1);
        assertThat(res.stores().get(0).storeId()).isEqualTo(storePublicId.toString());
        assertThat(res.stores().get(0).name()).isEqualTo("가게");
        // 내부 BIGSERIAL(999L) 이 어디에도 문자열로 새지 않는다.
        assertThat(res.stores().get(0).storeId()).doesNotContain("999");
    }

    @Test
    void 코드가_유효하지_않으면_예외() {
        when(valueOperations.get(anyString())).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.storemanager.api.common.ApiException.class, () -> service.pair("ZZZZZZZZ"));
    }
}
