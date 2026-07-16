package com.aisw.kkori.user.dto;

import com.aisw.kkori.user.config.ConsentPolicyProperties;
import com.aisw.kkori.user.domain.ConsentType;

import java.util.Arrays;
import java.util.List;

/**
 * 현재 동의 항목·버전 응답 (PRD consent.md 기능 2).
 *
 * <p>동의 화면이 검증 계약과 어긋나지 않기 위해 참조하는 검증 메타데이터다 — 서버는 검증
 * 판정(400·409)의 재료인 항목 집합·필수 여부·현재 버전만 제공하고, 표시 이름·동의서 본문·
 * 표시 순서는 프론트가 소유한다(소유 분담). 프론트는 {@code version}으로 표시할 문서 자산을
 * 선택하고 제출 시 그대로 되돌려 보낸다.
 */
public record ConsentCatalogResponse(List<CatalogItem> consents) {

    public record CatalogItem(ConsentType type, boolean required, int version) {
    }

    public static ConsentCatalogResponse from(ConsentPolicyProperties properties) {
        return new ConsentCatalogResponse(Arrays.stream(ConsentType.values())
                .map(type -> new CatalogItem(type, type.isRequired(), properties.versionOf(type)))
                .toList());
    }
}
