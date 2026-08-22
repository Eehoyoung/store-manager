package com.storemanager.api.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** /auth 요청·응답 DTO. AuthController/AuthService 내부에서만 쓰이므로 패키지 전용으로 둔다. */
record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 20) String phone,
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
        @Size(max = 20) String phone) {
}

record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword) {
}
