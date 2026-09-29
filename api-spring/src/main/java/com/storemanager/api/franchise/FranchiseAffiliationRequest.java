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
    /** 관리자는 app_user 가 없다(docs/26a) — 이 컬럼은 과거 데이터에만 값이 남는다. */
    @Column(name = "decided_by") private Long decidedBy;
    @Builder.Default @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "decided_at") private Instant decidedAt;
    /** 승인·거절·해제 사유(관리자 입력). */
    private String reason;
    @Column(name = "released_at") private Instant releasedAt;

    public void decide(boolean approved, String reason) {
        this.status = approved ? "APPROVED" : "REJECTED";
        this.decidedAt = Instant.now();
        this.reason = reason;
    }

    /** 승인된 소속을 해제한다 — 이후 본부 집계·개별 리뷰 조회에서 즉시 제외된다. */
    public void release(String reason, Instant at) {
        this.status = "RELEASED";
        this.releasedAt = at;
        this.reason = reason;
    }
}
