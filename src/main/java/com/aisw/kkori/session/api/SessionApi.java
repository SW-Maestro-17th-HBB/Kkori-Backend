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

    @Operation(summary = "면접 세션 종료",
            description = """
                    세션 종료 요청을 수리한다 — 세션 상태와 무관하게 종료(terminal) 수렴이 보장된다.
                    응답 202는 수리(accepted)의 의미이며 **종료는 비동기로 확정된다**: 진행 중(ACTIVE) 면접은
                    면접관 에이전트가 클로징 발화 후 룸을 삭제하는 시점에 끝나므로, 클라이언트는 응답이 아니라
                    룸 종료(DisconnectReason=ROOM_DELETED)로 종료를 감지해야 한다. PENDING은 룸 관측으로
                    에이전트 유무를 확인해 처리하고(면접 진행 중이면 정상 종료 유도), 에이전트 소실 상태는
                    즉시 종료 처리되며, 이미 종료된 세션에 대한 재호출은 멱등 no-op이다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "종료 요청 수리 — 실제 종료는 비동기 확정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "타인의 세션(S007)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "세션 없음(S006)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500",
                    description = "종료 처리의 LiveKit 왕복 실패(S008) — 재시도 가능, 종료는 자동 수렴"),
    })
    ApiResponse<Void> end(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "종료할 세션 id") Long sessionId);

    @Operation(summary = "면접 세션 재입장 토큰 발급",
            description = """
                    연결이 끊긴(INTERRUPTED — 에이전트 소실 중이면 AGENT_LOST 포함) 세션에 다시 입장할
                    토큰을 발급한다. identity는 최초 입장과 동일한 candidate-{sessionId}로 고정되며(면접관
                    에이전트의 재개 판정 기반), 토큰 만료는 재연결 deadline(이탈 시각 + 재연결 창)과
                    일치한다 — 창이 지나면 발급이 거부되고 세션은 자동 종료로 수렴한다.
                    응답 필드는 세션 생성과 동일하다(livekitToken·livekitUrl·livekitRoom).
                    **S009는 사유 불문 "세션 종료됨"으로 일괄 처리한다** — 사유별 분기 계약 없음.
                    재입장 후 일정 시간 내 면접관(AGENT)이 관측되지 않으면 무한 대기 없이 퇴장·종료
                    화면으로 처리해야 한다(고아 룸 완화 — 구체 대기값은 프론트 재량).""",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "재입장 토큰 발급 — id·livekitToken·livekitUrl·livekitRoom 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "타인의 세션(S007)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "세션 없음(S006)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "재입장 불가(S009) — 상태 부적합·이탈 미관측·재연결 창 만료"),
    })
    ApiResponse<InterviewSessionCreateResponse> rejoin(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "재입장할 세션 id") Long sessionId);
}
