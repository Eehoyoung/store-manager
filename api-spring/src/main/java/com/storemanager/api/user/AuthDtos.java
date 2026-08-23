package com.storemanager.api.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * /auth 요청·응답 DTO. AuthController/AuthService 내부에서만 쓰이므로 패키지 전용으로 둔다.
 *
 * ★ 화면에서 하이픈을 자동으로 넣어 주지만 그건 편의일 뿐이다. 서버는 신뢰 경계이므로
 *   형식을 여기서 다시 강제한다 — API 를 직접 호출하면 화면 로직은 지나가지 않는다.
 */
record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 50) String name,
        @Pattern(regexp = AuthPatterns.PHONE, message = "휴대폰 번호 형식이 올바르지 않습니다.")
        @Size(max = 20) String phone,
        @Pattern(regexp = AuthPatterns.FRANCHISE_CODE, message = "가맹코드 형식이 올바르지 않습니다.")
        @Size(max = 32) String franchiseCode,
        @NotBlank @Size(max = 100) String storeName,
        @NotBlank @Size(max = 300) String storeAddress) {
}

record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}

record UserSummary(String id, String name, String email) {
}

record AuthResponse(String accessToken, long expiresIn, UserSummary user) {
}

record UpdateProfileRequest(
        @NotBlank @Size(max = 50) String name,
        @Pattern(regexp = AuthPatterns.PHONE, message = "휴대폰 번호 형식이 올바르지 않습니다.")
        @Size(max = 20) String phone) {
}

record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword) {
}

/**
 * 검증 정규식. 선택 입력이라 빈 문자열도 통과시킨다 - 값을 넣었을 때만 형식을 본다.
 * 가맹코드 알파벳에서 I, O, 0, 1 을 뺐다(FranchiseService.CODE_ALPHABET - 전화로 불러줄 때
 * 혼동을 막기 위함). 여기서도 그 문자는 받지 않는다.
 */
final class AuthPatterns {
    static final String PHONE = "^$|^0[0-9]{1,2}-[0-9]{3,4}-[0-9]{4}$";
    static final String FRANCHISE_CODE = "^$|^[A-HJ-NP-Z2-9]{4,32}$";

    private AuthPatterns() {
    }
}
