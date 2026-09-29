package com.storemanager.api.admin;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시스템 관리자 이메일 화이트리스트 (docs/26a decisions.admin).
 *
 * <p>★ 2026-09-29 — {@code app.admin.user-ids}(app_user public UUID) 를 완전히 대체한다.
 * 관리자는 더 이상 {@code app_user} 행을 갖지 않고 OTP 로만 로그인한다({@code AdminAuthService}).
 * 이 클래스는 그 로그인 전(OTP 요청·검증) 자격 판정에만 쓰인다 — {@code /api/v1/admin/**} 자체의
 * 요청별 인가는 {@code SecurityConfig} 가 {@code SESSION_ADMIN} 권한으로 이미 강제한다.
 *
 * <p>값이 비면 아무도 관리자가 아니다(fail-closed, 기존 원칙 유지).
 */
@Component
public class AdminAccessGuard {

    private final Set<String> adminEmails;

    public AdminAccessGuard(@Value("${app.admin.emails:}") String emails) {
        this.adminEmails = Arrays.stream(emails.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdminEmail(String email) {
        return email != null && adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
