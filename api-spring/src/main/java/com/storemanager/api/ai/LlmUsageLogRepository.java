package com.storemanager.api.ai;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLog, Long> {
}
