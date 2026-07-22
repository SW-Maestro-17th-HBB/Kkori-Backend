package com.aisw.kkori.session.dto;

import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import jakarta.validation.constraints.NotNull;

/**
 * 면접 세션 생성 요청 (PRD 기능 1). 정의되지 않은 enum 값은 역직렬화 실패로
 * {@code INVALID_INPUT_VALUE}(C002)로 변환된다 — GlobalExceptionHandler 참조.
 */
@ResumeRequiredForThirtyMin
public record InterviewSessionCreateRequest(
        Long resumeId,
        @NotNull(message = "interviewType은 필수입니다") InterviewType interviewType,
        @NotNull(message = "position은 필수입니다") Position position
) {
}
