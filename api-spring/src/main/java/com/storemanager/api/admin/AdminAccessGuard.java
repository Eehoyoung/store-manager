package com.storemanager.api.admin;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import com.storemanager.api.user.AppUser;
import com.storemanager.api.user.AppUserRepository;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminAccessGuard {
    private final AppUserRepository users;
    private final Set<UUID> adminUserIds;

    public AdminAccessGuard(AppUserRepository users, @Value("${app.admin.user-ids:}") String userIds) {
        this.users = users;
        this.adminUserIds = Arrays.stream(userIds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
    }

    public AppUser requireAdmin(UUID publicId) {
        AppUser user = users.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!adminUserIds.contains(user.getPublicId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user;
    }
}
