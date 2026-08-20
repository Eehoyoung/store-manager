package com.storemanager.api.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record CreateStoreRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String brandName,
        @Size(max = 50) String category,
        @Size(max = 300) String address) {
}

record UpdateStoreRequest(
        @Size(max = 100) String name,
        @Size(max = 100) String brandName,
        @Size(max = 50) String category,
        @Size(max = 300) String address) {
}

record StoreResponse(
        String id,
        String name,
        String brandName,
        String category,
        String address,
        String status) {
}
