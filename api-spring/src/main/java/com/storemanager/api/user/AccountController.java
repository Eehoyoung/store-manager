package com.storemanager.api.user;

import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 본인 계정만 조회·수정할 수 있는 설정 API. 이메일은 변경하지 않는다. */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AuthService authService;
    private final AccountWithdrawalService withdrawalService;

    public AccountController(AuthService authService, AccountWithdrawalService withdrawalService) {
        this.authService = authService;
        this.withdrawalService = withdrawalService;
    }

    @GetMapping
    public AccountProfileResponse get() {
        return toResponse(authService.getCurrentUser(CurrentUser.publicId()));
    }

    @PatchMapping
    public AccountProfileResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return toResponse(authService.updateProfile(CurrentUser.publicId(), request));
    }

    /**
     * 회원 탈퇴 (개인정보보호법 제37조).
     *
     * <p>★ 되돌릴 수 없으므로 비밀번호를 다시 받는다. 세션이 탈취된 상태에서 계정이
     * 삭제되면 복구할 방법이 없다.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@Valid @RequestBody WithdrawRequest request) {
        withdrawalService.withdraw(CurrentUser.publicId(), request.password());
    }

    public record WithdrawRequest(@jakarta.validation.constraints.NotBlank String password) {
    }

    @PatchMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.publicId(), request);
    }

    private static AccountProfileResponse toResponse(AppUser user) {
        return new AccountProfileResponse(user.getPublicId().toString(), user.getEmail(), user.getName(),
                user.getPhone(), user.getStatus(), user.getCreatedAt(), user.getLastLoginAt());
    }
}

record AccountProfileResponse(
        String id,
        String email,
        String name,
        String phone,
        String status,
        Instant createdAt,
        Instant lastLoginAt) {
}
