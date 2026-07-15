package com.aisw.kkori.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @Scheduled} 활성화 (SSE keepalive 등). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
