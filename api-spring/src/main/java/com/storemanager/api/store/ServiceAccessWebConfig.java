package com.storemanager.api.store;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.security.CurrentUser;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 사장 API 한 곳 차단 — 구독이 서비스 불가한 매장은 {@code /api/v1/stores/{storeId}/**} 전체를
 * 막는다(2026-09-29 자동결제 전환).
 *
 * <p>★ 왜 한 곳인가 — 컨트롤러마다 게이트를 심으면 새 엔드포인트가 추가될 때 하나씩 빠뜨린다.
 * 인터셉터는 경로 패턴 하나로 앞으로 생길 하위 리소스(persona·facts·analytics·reviews·...)까지 덮는다.
 *
 * <p>★ 예외 둘.
 * <ul>
 *   <li>결제 관련 엔드포인트({@code billing} 패키지, 현재 {@code /api/v1/stores/{storeId}/billing/**})
 *       — 막으면 제한을 풀 방법이 없어진다. URL 문자열이 아니라 컨트롤러가 속한 패키지로
 *       판정한다 — 결제 코어가 경로를 다시 바꿔도(실제로 세션 중 한 번 바뀌었다) 깨지지 않는다.
 *   <li>{@code GET·DELETE /api/v1/stores/{storeId}} 정확히 — 매장 정보 확인·삭제는 막지 않는다.
 * </ul>
 *
 * <p>★ 매장 소유 여부는 여기서 최종 판단하지 않는다. 남의 매장이면 통과시켜 컨트롤러가 404를
 * 내게 한다 — 여기서 403/402를 내면 "이 storeId가 존재하고 결제 상태가 어떻다" 는 것을
 * 소유자가 아닌 요청자에게 흘리게 된다.
 */
@Component
public class ServiceAccessWebConfig implements WebMvcConfigurer, HandlerInterceptor {

    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final StoreServiceGate serviceGate;

    public ServiceAccessWebConfig(StoreRepository storeRepository, AppUserRepository appUserRepository,
            StoreServiceGate serviceGate) {
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.serviceGate = serviceGate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/stores/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (handlerMethod.getBeanType().getPackageName().startsWith("com.storemanager.api.billing")) {
            return true;
        }
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = request.getMethod();
        if ("/api/v1/stores/{storeId}".equals(pattern) && ("GET".equals(method) || "DELETE".equals(method))) {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> pathVars =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String storeIdRaw = pathVars == null ? null : pathVars.get("storeId");
        if (storeIdRaw == null) {
            return true;
        }

        UUID storePublicId;
        try {
            storePublicId = UUID.fromString(storeIdRaw);
        } catch (IllegalArgumentException e) {
            return true; // 형식이 틀린 id는 컨트롤러가 처리한다.
        }

        Store store = storeRepository.findByPublicIdAndDeletedAtIsNull(storePublicId).orElse(null);
        if (store == null) {
            return true;
        }
        AppUser me = appUserRepository.findByPublicId(CurrentUser.publicId()).orElse(null);
        if (me == null || !me.getId().equals(store.getOwnerId())) {
            return true; // 내 매장이 아니면 컨트롤러가 404를 내게 한다.
        }
        if (!serviceGate.isSubscriptionServiceable(store.getId())) {
            throw new ApiException(ErrorCode.SUBSCRIPTION_PAYMENT_REQUIRED);
        }
        return true;
    }
}
