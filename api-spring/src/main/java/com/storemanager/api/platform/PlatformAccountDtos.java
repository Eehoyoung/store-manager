package com.storemanager.api.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record RegisterPlatformAccountRequest(
        @NotBlank @Size(max = 20) String platform,
        @NotBlank @Size(max = 128) String loginId,
        @NotBlank @Size(max = 256) String password,
        @NotNull UUID storeId) {
}

record PlatformAccountResponse(
        String id,
        String platform,
        String maskedLoginId,
        String linkStatus,
        String verificationStatus,
        String statusMessage,
        String lastErrorCode,
        Instant verifiedAt,
        List<PlatformStoreLinkResponse> links) {
}

record PlatformStoreLinkResponse(
        String storeId,
        String platformStoreId,
        String storeNameSnapshot) {
}
