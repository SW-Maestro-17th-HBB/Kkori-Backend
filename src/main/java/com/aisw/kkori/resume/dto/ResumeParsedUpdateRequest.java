package com.aisw.kkori.resume.dto;

import com.aisw.kkori.resume.domain.StructuredData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 파싱 결과 수정 요청. 필드의 {@code @Valid}가 검증기를 StructuredData 내부 규칙(배열 내 null 거부)까지 들여보낸다. */
public record ResumeParsedUpdateRequest(
        @NotNull @Valid StructuredData structuredData
) {
}
