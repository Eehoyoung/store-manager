package com.storemanager.api.franchise;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "franchise_affiliation_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FranchiseAffiliationRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Builder.Default @Column(name = "public_id", nullable = false) private UUID publicId = UUID.randomUUID();
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "join_code_id", nullable = false) private Long joinCodeId;
    @Builder.Default @Column(nullable = false) private String status = "PENDING";
    @Column(name = "decided_by") private Long decidedBy;
    @Builder.Default @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "decided_at") private Instant decidedAt;

    public void decide(boolean approved, Long adminId) {
        this.status = approved ? "APPROVED" : "REJECTED";
        this.decidedBy = adminId;
        this.decidedAt = Instant.now();
    }
}
