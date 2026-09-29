package com.storemanager.api.security;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext 에서 인증된 시스템 관리자의 이메일을 꺼내는 헬퍼.
 *
 * <p>관리자는 {@code app_user} 행이 없다(docs/26a decisions.admin) — principal 은
 * {@link CurrentUser} 처럼 UUID 가 아니라 이메일 문자열이다. {@code /api/v1/admin/**} 은
 * SecurityConfig 가 이미 {@code SESSION_ADMIN} 권한을 요구하므로, 여기서는 파싱만 한다.
 */
public final class CurrentAdmin {

    private CurrentAdmin() {
    }

    public static String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 감사로그용 비식별 참조값 — SHA-256(소문자 이메일) hex 앞 16자(docs/26a decisions.admin).
     * 감사로그에 이메일 원문을 남기지 않기 위한 것이다.
     */
    public static String ref() {
        return ref(email());
    }

    public static String ref(String email) {
        try {
            String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
