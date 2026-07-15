package com.aisw.kkori.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.s3.*} 설정 바인딩.
 *
 * <p>@Value 산개 대신 타입 안전한 묶음으로 주입한다 — 항목 추가 시(presigned 만료 시간 등) 여기에 확장.
 */
@ConfigurationProperties("app.s3")
public record S3Properties(
        String bucket
) {
}
