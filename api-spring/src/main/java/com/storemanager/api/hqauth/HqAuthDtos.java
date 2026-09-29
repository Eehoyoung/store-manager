package com.storemanager.api.hqauth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class HqAuthDtos {
    private HqAuthDtos() {
    }

    record EmailRequest(@NotBlank @Email @Size(max = 255) String email) {
    }

    record VerifyRequest(@NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    record SentResponse(boolean sent) {
    }

    record HqUserSummary(String name, String email) {
    }

    record HqAuthResponse(String accessToken, String expiresAt, String sessionType, HqUserSummary user) {
    }
}
