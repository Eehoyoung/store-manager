package com.storemanager.api.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * store_persona 테이블 매핑 (docs/11 §2.6). PK = store_id (단순 PK 매핑, 별도 연관관계 없음).
 * personaSeed 는 매장 생성 시 SecureRandom 으로 부여해 동일 브랜드 매장 간 문체 분화를 유도한다.
 */
@Entity
@Table(name = "store_persona")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StorePersona {

    @Id
    @Column(name = "store_id")
    private Long storeId;

    @Builder.Default
    @Column(nullable = false)
    private String tone = "POLITE";

    @Builder.Default
    @Column(name = "use_emoji", nullable = false)
    private boolean useEmoji = true;

    @Builder.Default
    @Column(name = "emoji_level", nullable = false)
    // 0=사용 안 함 · 1=1개 이하 · 2=2~3개(기본) · 3=자유 — V34 에서 기본값을 2 로 올렸다.
    private short emojiLevel = 2;

    @Builder.Default
    @Column(name = "customer_title", nullable = false)
    private String customerTitle = "고객님";

    private String signature;

    @Column(name = "opening_style")
    private String openingStyle;

    // TEXT[] — Hibernate 6 이 PostgreSQL 배열 타입을 기본 지원한다.
    @Builder.Default
    @Column(name = "banned_words", nullable = false)
    private String[] bannedWords = new String[0];

    @Builder.Default
    @Column(name = "length_min", nullable = false)
    private short lengthMin = 60;

    @Builder.Default
    @Column(name = "length_max", nullable = false)
    private short lengthMax = 150;

    @Builder.Default
    @Column(name = "delay_hours", nullable = false)
    private short delayHours = 6;

    // JSONB — Sprint 1 에서는 원본 JSON 문자열만 보관(파싱은 페르소나 API 구현 시 추가)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "publish_windows", nullable = false, columnDefinition = "jsonb")
    private String publishWindows = "[]";

    /**
     * 자동 게시 여부. false 면 안전검사를 통과해도 예약하지 않고 사장님 확인 대기로 남긴다.
     *
     * <p><b>★ 개인정보 보호법 제37조의2(자동화된 결정에 대한 거부권)의 이행 수단이다.</b>
     * 처리방침 §9.4 가 "요청하면 자동 게시를 중지한다" 고 약속한다 — 그 약속을 지킬 수단이
     * 없으면 지키지 못할 약속을 적어 둔 것이 된다. 이 필드를 지우려면 방침부터 고쳐라.
     *
     * <p><b>★ 끈다고 초안 생성을 멈추지 않는다.</b> 멈추면 사장님이 빈손이 된다 —
     * 네이버 경로와 같은 취급이다(초안은 주되 게시는 사람이 한다).
     */
    @Builder.Default
    @Column(name = "auto_publish", nullable = false)
    private boolean autoPublish = true;

    @Column(name = "persona_seed", nullable = false)
    private int personaSeed;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * PUT /stores/{storeId}/persona (Sprint 5 P1). 전체 필드 교체.
     * store_persona 에는 unified_review/reply_draft 와 달리 updated_at 트리거가 없어(docs/11 §2.6) 여기서 직접 갱신한다.
     */
    /** 자동 게시 중지·재개. 페르소나 전체 교체(applyUpdate)와 분리해 둔다 — 말투 설정이 아니라 권리 행사다. */
    public void setAutoPublish(boolean autoPublish) {
        this.autoPublish = autoPublish;
        this.updatedAt = Instant.now();
    }

    public void applyUpdate(String tone, boolean useEmoji, short emojiLevel, String customerTitle, String signature,
            String openingStyle, String[] bannedWords, short lengthMin, short lengthMax, short delayHours,
            String publishWindowsJson) {
        this.tone = tone;
        this.useEmoji = useEmoji;
        this.emojiLevel = emojiLevel;
        this.customerTitle = customerTitle;
        this.signature = signature;
        this.openingStyle = openingStyle;
        this.bannedWords = bannedWords == null ? new String[0] : bannedWords;
        this.lengthMin = lengthMin;
        this.lengthMax = lengthMax;
        this.delayHours = delayHours;
        this.publishWindows = publishWindowsJson == null ? "[]" : publishWindowsJson;
        this.updatedAt = Instant.now();
    }
}
