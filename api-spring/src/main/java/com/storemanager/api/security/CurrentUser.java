package com.storemanager.api.security;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** SecurityContext 에서 인증된 사용자의 public_id(UUID)를 꺼내는 헬퍼. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID publicId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
    }
}
