package com.aisw.kkori.session.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * 에이전트 디스패치 metadata — Kkori-AI와 공유하는 계약 payload (agent-dispatch.md 디스패치 계약).
 *
 * <p>직렬화 자구(compact·필드 순서 고정·비ASCII 원문 유지)가 계약 픽스처로 양 레포 테스트에서
 * 검증되므로, 필드 순서는 선언 순서에 더해 {@code @JsonPropertyOrder}로 명시 고정한다.
 * {@code resumeContext}는 없으면 필드 자체를 생략한다(null·빈 문자열 금지 — NON_NULL 직렬화).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"sessionId", "interviewType", "position", "resumeContext"})
public record DispatchMetadata(
        String sessionId,
        String interviewType,
        String position,
        String resumeContext
) {

    public DispatchMetadata {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(interviewType, "interviewType");
        Objects.requireNonNull(position, "position");
    }
}
