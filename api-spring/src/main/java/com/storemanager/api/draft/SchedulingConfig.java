package com.storemanager.api.draft;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** PublishScheduler(@Scheduled) 를 쓰기 위한 스케줄링 활성화. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
