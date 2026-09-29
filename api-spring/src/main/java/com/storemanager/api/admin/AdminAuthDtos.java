package com.storemanager.api.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class AdminAuthDtos {
    private AdminAuthDtos() {
    }

    record EmailRequest(@NotBlank @Email @Size(max = 255) String email) {
    }

    record VerifyRequest(@NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    record SentResponse(boolean sent) {
    }

    record AdminAuthResponse(String accessToken, String expiresAt, String sessionType) {
    }
}
