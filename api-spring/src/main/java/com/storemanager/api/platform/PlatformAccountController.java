package com.storemanager.api.platform;

import com.storemanager.api.crypto.PlatformAccount;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
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
    public PlatformAccountResponse register(@Valid @RequestBody RegisterPlatformAccountRequest request,
            HttpServletRequest httpRequest) {
        return service.register(CurrentUser.publicId(), request, clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID accountId, HttpServletRequest request) {
        service.revoke(CurrentUser.publicId(), accountId, clientIp(request), request.getHeader("User-Agent"));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
        try {
            java.net.InetAddress.getByName(value);
            return value;
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
