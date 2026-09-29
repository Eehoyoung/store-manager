package com.storemanager.api.franchise;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** 시스템 콘솔의 가맹본부 관리 API DTO (docs/26a endpoints.adminConsole). */
public final class FranchiseDtos {

    private FranchiseDtos() {
    }

    public record CreateFranchiseRequest(
            @NotBlank @Size(max = 100) String brandName,
            @NotBlank @Size(max = 50) String memberName,
            @NotBlank @Email @Size(max = 255) String memberEmail,
            @Size(max = 100) String memberTitle,
            boolean issueJoinCode,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record CreateFranchiseResponse(String brandName, String memberId, String joinCode) {
    }

    public record FranchiseListItem(String brandName, String status, long memberCount, long activeMemberCount,
            long approvedStoreCount, long pendingRequestCount, boolean joinCodeActive, String joinCodeRotatedAt,
            String lastHqLoginAt, String createdAt) {
    }

    public record JoinCodeInfo(boolean active, String rotatedAt, String rotatedByRef) {
    }

    public record MemberItem(String memberId, String name, String email, String title, String status,
            String lastLoginAt, String invitedAt, String revokedAt) {
    }

    public record ApprovedStoreItem(String storeId, String storeName, String address, String approvedAt) {
    }

    public record FranchiseDetailResponse(String brandName, String status, String createdAt, JoinCodeInfo joinCode,
            List<MemberItem> members, List<ApprovedStoreItem> approvedStores) {
    }

    public record StatusRequest(@NotBlank String status, @NotBlank @Size(max = 200) String reason) {
    }

    public record AddMemberRequest(@NotBlank @Size(max = 50) String name, @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 100) String title, @NotBlank @Size(max = 200) String reason) {
    }

    public record AddMemberResponse(String memberId) {
    }

    public record UpdateMemberRequest(@Size(max = 50) String name, @Size(max = 100) String title, String status,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record JoinCodeResponse(String joinCode) {
    }

    public record AuditLogItem(String action, String actorType, Instant createdAt, String detail) {
    }

    public record AffiliationItem(String id, String brandName, String requesterName, String requesterEmail,
            String storeName, String storeAddress, String status, Instant requestedAt, Instant decidedAt,
            String reason, String hqConsentVersion, Instant hqConsentAt, boolean reviewSharingAgreed) {
    }

    public record DecisionRequest(@NotBlank String decision, String reason) {
    }
}
