package com.storemanager.api.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    java.util.List<AuditLog> findByActionOrderByCreatedAtAsc(String action);
    boolean existsByActionAndActorId(String action, Long actorId);
    boolean existsByActionAndActorIdAndCreatedAtAfter(String action, Long actorId, java.time.Instant after);

    /**
     * 시스템 콘솔 감사기록(docs/26a endpoints.adminConsole audit-logs) — {@code detail} JSONB 의
     * {@code brandName} 키로 그 브랜드 관련 변경을 모은다. FranchiseService 가 쓰기 액션마다
     * detail 에 {@code brandName} 을 항상 채워 두므로 이 쿼리 하나로 전부 잡힌다.
     */
    @Query(value = "SELECT * FROM audit_log WHERE detail ->> 'brandName' = :brand "
            + "ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    java.util.List<AuditLog> findByBrandDetail(@Param("brand") String brand, @Param("limit") int limit);
}
