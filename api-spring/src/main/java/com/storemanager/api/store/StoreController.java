package com.storemanager.api.store;

import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §3. GET /stores/{id}/summary 는 Sprint 1 범위 밖(리뷰 테이블 미구현)이라 만들지 않는다. */
@RestController
@RequestMapping("/api/v1/stores")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping
    public List<StoreResponse> list() {
        return storeService.listMyStores(CurrentUser.publicId()).stream()
                .map(StoreController::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse create(@Valid @RequestBody CreateStoreRequest req) {
        return toResponse(storeService.createStore(CurrentUser.publicId(), req));
    }

    @GetMapping("/{storeId}")
    public StoreResponse get(@PathVariable UUID storeId) {
        return toResponse(storeService.getStore(CurrentUser.publicId(), storeId));
    }

    @PatchMapping("/{storeId}")
    public StoreResponse update(@PathVariable UUID storeId, @RequestBody UpdateStoreRequest req) {
        return toResponse(storeService.updateStore(CurrentUser.publicId(), storeId, req));
    }

    @DeleteMapping("/{storeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID storeId) {
        storeService.deleteStore(CurrentUser.publicId(), storeId);
    }

    private static StoreResponse toResponse(Store s) {
        return new StoreResponse(s.getPublicId().toString(), s.getName(), s.getBrandName(),
                s.getCategory(), s.getAddress(), s.getStatus(), s.getActivatedAt());
    }
}
