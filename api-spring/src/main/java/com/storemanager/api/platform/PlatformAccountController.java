package com.storemanager.api.platform;

import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-accounts")
public class PlatformAccountController {

    private final PlatformAccountService service;

    public PlatformAccountController(PlatformAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlatformAccountResponse> list() {
        return service.list(CurrentUser.publicId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformAccountResponse register(@Valid @RequestBody RegisterPlatformAccountRequest request) {
        return service.register(CurrentUser.publicId(), request);
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID accountId) {
        service.revoke(CurrentUser.publicId(), accountId);
    }
}
