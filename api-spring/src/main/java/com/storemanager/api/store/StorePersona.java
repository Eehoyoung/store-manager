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
    private short emojiLevel = 1;

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
    @Column(name = "auto_publish", nullable = false)
    private boolean autoPublish = false;

    @Builder.Default
    @Column(name = "auto_min_rating", nullable = false)
    private short autoMinRating = 4;

    @Builder.Default
    @Column(name = "auto_max_risk", nullable = false)
    private short autoMaxRisk = 1;

    @Builder.Default
    @Column(name = "delay_hours", nullable = false)
    private short delayHours = 6;

    // JSONB — Sprint 1 에서는 원본 JSON 문자열만 보관(파싱은 페르소나 API 구현 시 추가)
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "publish_windows", nullable = false, columnDefinition = "jsonb")
    private String publishWindows = "[]";

    @Column(name = "persona_seed", nullable = false)
    private int personaSeed;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * PUT /stores/{storeId}/persona (Sprint 5 P1). 전체 필드 교체.
     * ★ autoMaxRisk 는 호출부(PersonaService)에서 0~2 로 이미 검증된 값만 들어온다(절대규칙 3 —
     * autoMaxRisk 로 자동게시 위험도 하한을 3 이상으로 설정할 수 없어야 한다). 이 메서드는 재검증하지 않는다.
     * store_persona 에는 unified_review/reply_draft 와 달리 updated_at 트리거가 없어(docs/11 §2.6) 여기서 직접 갱신한다.
     */
    public void applyUpdate(String tone, boolean useEmoji, short emojiLevel, String customerTitle, String signature,
            String openingStyle, String[] bannedWords, short lengthMin, short lengthMax, boolean autoPublish,
            short autoMinRating, short autoMaxRisk, short delayHours, String publishWindowsJson) {
        this.tone = tone;
        this.useEmoji = useEmoji;
        this.emojiLevel = emojiLevel;
        this.customerTitle = customerTitle;
        this.signature = signature;
        this.openingStyle = openingStyle;
        this.bannedWords = bannedWords == null ? new String[0] : bannedWords;
        this.lengthMin = lengthMin;
        this.lengthMax = lengthMax;
        this.autoPublish = autoPublish;
        this.autoMinRating = autoMinRating;
        this.autoMaxRisk = autoMaxRisk;
        this.delayHours = delayHours;
        this.publishWindows = publishWindowsJson == null ? "[]" : publishWindowsJson;
        this.updatedAt = Instant.now();
    }
}
