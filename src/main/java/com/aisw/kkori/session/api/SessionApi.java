package com.aisw.kkori.session.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.dto.InterviewSessionCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Session", description = "면접 세션 — 세션 생성·LiveKit 룸/토큰 발급")
public interface SessionApi {

    @Operation(summary = "면접 세션 생성",
            description = """
                    면접 유형(THIRTY_MIN/FIVE_MIN)·직무(BACKEND/FRONTEND)·대상 이력서를 받아
                    면접 세션(PENDING)을 생성하고, 세션 전용 LiveKit 룸과 입장 토큰(JWT)·서버 URL을 발급한다.
                    THIRTY_MIN은 분석 완료(EMBEDDED)된 본인 이력서가 필수이고, FIVE_MIN은 이력서를 생략할 수 있다.
                    기존 PENDING 세션은 새 세션이 자동 교체(ABORTED)하며, 진행 중 세션이 있으면 409로 거부된다.
                    참가자 신원은 candidate-{sessionId}로 서버가 확정한다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "세션 생성 — id·livekitRoom·livekitToken·livekitUrl 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "필드 누락·미정의 유형/직무·THIRTY_MIN의 resumeId 누락(C002, fieldErrors 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "타인의 이력서(R009)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "이력서 없음·삭제됨(R008)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이력서 분석 진행 중(R010)·분석 실패 상태(R011)·진행 중 면접 세션 존재(S003)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500",
                    description = "룸 생성 실패(S002)·토큰 발급 실패(S001)"),
    })
    ApiResponse<InterviewSessionCreateResponse> create(
            @Parameter(hidden = true) Long userId,
            InterviewSessionCreateRequest request);
}
