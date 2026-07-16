package com.aisw.kkori.user.config;

import com.aisw.kkori.user.domain.ConsentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 동의서 버전 설정 ({@code consent.*}).
 *
 * <p>{@code versions}는 동의 항목별 현재 동의서 버전이다(PRD consent.md 기능 1).
 * 동의 제출(가입·복구·선택 동의 변경)의 버전 대조와 {@code AGREED} 기록 버전의 원천으로,
 * 버전은 단조 증가만 허용한다(롤백 금지 — 정정도 새 버전 발행, fix-forward).
 *
 * <p>전 항목이 1 이상으로 주입돼야 하며, 누락·비정상 값은 부팅 시점에 실패시킨다(fail-fast).
 * enum에 항목이 추가되면 설정이 따라올 때까지 부팅이 깨진다. 검증 후 {@link Map#copyOf(Map)}로
 * 불변화해 접근자를 통한 런타임 변경을 차단한다("현재 버전 = 설정 단일 원천" 계약).
 */
@ConfigurationProperties(prefix = "consent")
public record ConsentPolicyProperties(
        Map<ConsentType, Integer> versions
) {

    public ConsentPolicyProperties {
        if (versions == null) {
            throw new IllegalArgumentException("consent.versions은(는) 필수 설정입니다");
        }
        for (ConsentType type : ConsentType.values()) {
            Integer version = versions.get(type);
            if (version == null || version < 1) {
                throw new IllegalArgumentException(
                        "consent.versions.%s은(는) 1 이상의 정수여야 합니다".formatted(type.getValue()));
            }
        }
        versions = Map.copyOf(versions);
    }

    /** 항목의 현재 동의서 버전. primitive 반환 — 호출부의 Integer 참조 비교 실수를 차단한다. */
    public int versionOf(ConsentType type) {
        return versions.get(type);
    }
}
