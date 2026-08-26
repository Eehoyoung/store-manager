package com.storemanager.api.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림톡 링크 전용 일회용 접근 토큰.
 *
 * <p>카카오톡 인앱 브라우저에는 로그인 세션이 남지 않는다. 매번 로그인시키면 사장님은
 * 두 번째부터 링크를 누르지 않고, 그러면 위험 리뷰가 방치된다.
 *
 * <p><b>★ 범위를 좁혀서 위험을 감수한다.</b> 이 토큰으로 할 수 있는 것은 초안 하나를 보고
 * 승인하거나 거절하는 것뿐이다. 세션을 만들지 않으므로 다른 화면으로 넘어갈 수단 자체가 없다.
 * 링크가 전달·캡처되어도 잃는 것은 그 리뷰 하나의 열람이지 계정이 아니다.
 */
@Entity
@Table(name = "draft_access_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DraftAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ★ SHA-256(평문). 평문을 저장하면 DB 유출이 곧 유효한 링크 생성이 된다. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 승인·거절이 실행된 시각. 채워지면 더 이상 액션을 받지 않는다(조회는 계속 가능). */
    @Column(name = "used_at")
    private Instant usedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** 조회할 수 있는가. 만료만 본다 — 이미 처리한 건도 결과를 다시 볼 수 있어야 한다. */
    public boolean isReadable(Instant now) {
        return now.isBefore(expiresAt);
    }

    /** 승인·거절을 받을 수 있는가. 만료 전이고 아직 쓰지 않았을 때만. */
    public boolean isActionable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }
}
