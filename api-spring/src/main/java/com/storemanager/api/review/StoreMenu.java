package com.storemanager.api.review;

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

/** store_menu 테이블 매핑 (docs/11 §2.6). */
@Entity
@Table(name = "store_menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StoreMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    private String platform;

    @Column(name = "menu_id")
    private String menuId; // DataAPI MENUID. collect-result 계약에는 현재 이름만 오므로 항상 null

    @Column(name = "menu_name", nullable = false)
    private String menuName; // MENUNM

    @Builder.Default
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen = Instant.now();
}
