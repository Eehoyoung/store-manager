package com.storemanager.api.franchise;

import com.storemanager.api.franchise.FranchiseDtos.ProvisionRequest;
import com.storemanager.api.franchise.FranchiseDtos.ProvisionResponse;
import com.storemanager.api.internal.InternalTokenVerifier;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 전용 가맹본부 계정·가입코드 발급 API. HQ 공개 API의 조회 전용 규칙과 분리한다. */
@RestController
@RequestMapping("/internal/franchises")
public class FranchiseProvisioningController {

    private final FranchiseService franchiseService;
    private final InternalTokenVerifier tokenVerifier;

    public FranchiseProvisioningController(FranchiseService franchiseService, InternalTokenVerifier tokenVerifier) {
        this.franchiseService = franchiseService;
        this.tokenVerifier = tokenVerifier;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionResponse provision(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody ProvisionRequest req) {
        tokenVerifier.verify(token);
        return franchiseService.provision(req);
    }
}
