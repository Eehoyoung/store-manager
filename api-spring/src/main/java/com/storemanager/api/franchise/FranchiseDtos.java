package com.storemanager.api.franchise;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class FranchiseDtos {

    private FranchiseDtos() {
    }

    record ProvisionRequest(
            @NotBlank @Size(max = 100) String brandName,
            @NotBlank @Email @Size(max = 255) String hqEmail,
            @NotBlank @Size(min = 8, max = 100) String hqPassword,
            @NotBlank @Size(max = 50) String hqName,
            @Size(max = 20) String hqPhone) {
    }

    record ProvisionResponse(String brandName, String hqUserId, String franchiseCode) {
    }
}
