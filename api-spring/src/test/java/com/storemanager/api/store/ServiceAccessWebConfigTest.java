package com.storemanager.api.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storemanager.api.billing.BillingController;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 사장 API 한 곳 차단(ServiceAccessWebConfig) 단위테스트.
 * 실제 디스패치 없이 preHandle 만 직접 부른다 — HandlerMapping 이 채우는 두 속성
 * (BEST_MATCHING_PATTERN_ATTRIBUTE · URI_TEMPLATE_VARIABLES_ATTRIBUTE)을 직접 설정한다.
 */
class ServiceAccessWebConfigTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authAs(UUID publicId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(publicId.toString(), "", java.util.List.of()));
    }

    private static MockHttpServletRequest request(String pattern, String method, String storeId) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/v1/stores/" + storeId + "/persona");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("storeId", storeId));
        return req;
    }

    private static HandlerMethod handlerOf(Class<?> beanType) {
        HandlerMethod hm = mock(HandlerMethod.class);
        when(hm.getBeanType()).thenAnswer(inv -> beanType);
        return hm;
    }

    @Test
    void 서비스_불가한_내_매장이면_402를_던진다() {
        UUID ownerPublicId = UUID.randomUUID();
        authAs(ownerPublicId);
        UUID storeId = UUID.randomUUID();
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        StoreServiceGate gate = mock(StoreServiceGate.class);
        Store store = Store.builder().id(1L).publicId(storeId).ownerId(9L).name("가게").status("ACTIVE")
                .activatedAt(Instant.now()).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storeId)).thenReturn(Optional.of(store));
        when(appUserRepository.findByPublicId(ownerPublicId))
                .thenReturn(Optional.of(AppUser.builder().id(9L).publicId(ownerPublicId).email("a@b.com").name("사장").build()));
        when(gate.isSubscriptionServiceable(1L)).thenReturn(false);
        ServiceAccessWebConfig interceptor = new ServiceAccessWebConfig(storeRepository, appUserRepository, gate);

        assertThatThrownBy(() -> interceptor.preHandle(
                request("/api/v1/stores/{storeId}/persona", "GET", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(Object.class)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SUBSCRIPTION_PAYMENT_REQUIRED));
    }

    @Test
    void 서비스_가능하면_통과한다() {
        UUID ownerPublicId = UUID.randomUUID();
        authAs(ownerPublicId);
        UUID storeId = UUID.randomUUID();
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        StoreServiceGate gate = mock(StoreServiceGate.class);
        Store store = Store.builder().id(1L).publicId(storeId).ownerId(9L).name("가게").status("ACTIVE")
                .activatedAt(Instant.now()).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storeId)).thenReturn(Optional.of(store));
        when(appUserRepository.findByPublicId(ownerPublicId))
                .thenReturn(Optional.of(AppUser.builder().id(9L).publicId(ownerPublicId).email("a@b.com").name("사장").build()));
        when(gate.isSubscriptionServiceable(1L)).thenReturn(true);
        ServiceAccessWebConfig interceptor = new ServiceAccessWebConfig(storeRepository, appUserRepository, gate);

        boolean result = interceptor.preHandle(
                request("/api/v1/stores/{storeId}/persona", "GET", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(Object.class));

        assertThat(result).isTrue();
    }

    @Test
    void 결제_컨트롤러는_구독_상태와_무관하게_통과한다() {
        UUID ownerPublicId = UUID.randomUUID();
        authAs(ownerPublicId);
        UUID storeId = UUID.randomUUID();
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        StoreServiceGate gate = mock(StoreServiceGate.class);
        // 저장소를 부르지 않아야 한다 — stub 을 등록하지 않고, 호출되면 UnnecessaryStubbing 없이도
        // NPE/Optional.empty 로 드러나므로 별도 verify 없이도 안전하다.
        ServiceAccessWebConfig interceptor = new ServiceAccessWebConfig(storeRepository, appUserRepository, gate);

        boolean result = interceptor.preHandle(
                request("/api/v1/stores/{storeId}/subscription", "GET", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(BillingController.class));

        assertThat(result).isTrue();
    }

    @Test
    void 매장_정보_조회와_삭제는_예외로_통과한다() {
        UUID ownerPublicId = UUID.randomUUID();
        authAs(ownerPublicId);
        UUID storeId = UUID.randomUUID();
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        StoreServiceGate gate = mock(StoreServiceGate.class);
        ServiceAccessWebConfig interceptor = new ServiceAccessWebConfig(storeRepository, appUserRepository, gate);

        assertThat(interceptor.preHandle(
                request("/api/v1/stores/{storeId}", "GET", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(StoreController.class))).isTrue();
        assertThat(interceptor.preHandle(
                request("/api/v1/stores/{storeId}", "DELETE", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(StoreController.class))).isTrue();
    }

    @Test
    void 내_매장이_아니면_통과시켜_컨트롤러가_404를_내게_한다() {
        UUID ownerPublicId = UUID.randomUUID();
        authAs(ownerPublicId);
        UUID storeId = UUID.randomUUID();
        StoreRepository storeRepository = mock(StoreRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        StoreServiceGate gate = mock(StoreServiceGate.class);
        Store someoneElsesStore = Store.builder().id(1L).publicId(storeId).ownerId(999L).name("남의가게")
                .status("ACTIVE").activatedAt(Instant.now()).build();
        when(storeRepository.findByPublicIdAndDeletedAtIsNull(storeId)).thenReturn(Optional.of(someoneElsesStore));
        when(appUserRepository.findByPublicId(ownerPublicId))
                .thenReturn(Optional.of(AppUser.builder().id(9L).publicId(ownerPublicId).email("a@b.com").name("사장").build()));
        ServiceAccessWebConfig interceptor = new ServiceAccessWebConfig(storeRepository, appUserRepository, gate);

        boolean result = interceptor.preHandle(
                request("/api/v1/stores/{storeId}/persona", "GET", storeId.toString()),
                new MockHttpServletResponse(), handlerOf(Object.class));

        assertThat(result).isTrue();
    }
}
