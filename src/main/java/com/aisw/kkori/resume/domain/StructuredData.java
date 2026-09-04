package com.aisw.kkori.resume.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 구조화된 이력서 데이터 — {@code resumes.structured_data}(jsonb) 스키마의 유일한 정의.
 *
 * <p>Worker와 공유하는 계약 문서 (쓰기: Worker의 LLM 구조화 결과 저장 / 읽기: Spring 조회·수정 API,
 * Worker의 REINDEX 입력). 필드 추가·변경 시 Worker와 합의 필요.
 *
 * <p>검증 방침 (PRD §4): **형태는 엄격, 내용은 관대** —
 * 읽기는 unknown 필드 무시(LLM 출력 유동성 흡수), 필드 누락·빈 배열은 유효
 * (내용의 올바름은 시스템이 판정하지 않음), 단 배열 내 null 요소는 거부(청킹 시 지뢰).
 *
 * <p>unknown 무시는 수정 요청(PATCH) 역직렬화에도 적용된다 — 의도된 동작(PRD §4 제약사항).
 * 클라이언트는 타입 명세를 공유하는 자사 프론트뿐이고, 수정 응답이 저장 결과 전체를 되돌려주므로
 * 무시된 필드는 왕복에서 드러난다. PATCH 전용 스키마 사본은 정의 원천 단일화와 상충해 두지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredData(
        @Valid Profile profile,
        List<@NotNull @Valid Skill> skills,
        List<@NotNull @Valid Project> projects,
        List<@NotNull @Valid Experience> experiences
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            String name,
            String email
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Skill(
            String category,
            List<@NotNull String> items
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(
            String name,
            String role,
            String description,
            List<@NotNull String> techStacks
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Experience(
            String title,
            String description
    ) {
    }
}
