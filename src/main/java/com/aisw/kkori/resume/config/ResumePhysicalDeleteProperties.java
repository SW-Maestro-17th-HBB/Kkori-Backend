package com.aisw.kkori.resume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 이력서 물리 삭제 배치 설정 ({@code resume.*} — PRD deletion.md 기능 5).
 *
 * <ul>
 * <li>{@code physicalDeleteInterval} — 배치 회차 간격(fixedDelay).</li>
 * <li>{@code physicalDeleteDelay} — soft delete 후 물리 삭제까지의 최소 지연. 업로드 경로의
 *     "S3 객체 존재 확인 → DB 저장" 창과 배치의 S3 삭제가 겹치지 않게 하는 여유다. 분석 진행 중
 *     이력서의 보류 상한(지연의 배수)은 코드 상수로 둔다(배치 구현 참조).</li>
 * </ul>
 *
 * <p>잘못된 설정(0 이하 기간)은 부팅 시점에 실패시킨다(fail-fast).
 */
@ConfigurationProperties(prefix = "resume")
public record ResumePhysicalDeleteProperties(
        Duration physicalDeleteInterval,
        Duration physicalDeleteDelay
) {

    public ResumePhysicalDeleteProperties {
        requirePositive(physicalDeleteInterval, "resume.physical-delete-interval");
        requirePositive(physicalDeleteDelay, "resume.physical-delete-delay");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("%s은(는) 0보다 큰 기간이어야 합니다".formatted(name));
        }
    }
}
