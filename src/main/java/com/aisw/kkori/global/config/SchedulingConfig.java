package com.aisw.kkori.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 활성화 — SSE keepalive, 세션 수렴 스위퍼, 파기·이력서 물리 삭제·RT 청소 배치.
 *
 * <p>스케줄러 스레드 풀 크기는 공통 {@code application.yaml}의
 * {@code spring.task.scheduling.pool.size}로 관리한다 — Boot 기본값 1이면 모든 주기 작업이
 * 한 스레드를 나눠 써서, 파기 회차가 S3·카카오 왕복으로 길어지는 동안 스위퍼가 밀린다
 * (PRD deletion.md 공통: 배치 실행·설정).
 *
 * <p>배치 빈들은 {@code app.batch.enabled}(기본 true)로 등록을 끌 수 있다 — 통합 테스트가
 * 과거 시각을 시딩한 탈퇴 건을 백그라운드 회차가 파기해 버리지 않도록 테스트 컨텍스트에서 끈다
 * ({@code TestcontainersConfiguration}). 배치 로직은 스케줄 메서드를 직접 호출해 검증한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
