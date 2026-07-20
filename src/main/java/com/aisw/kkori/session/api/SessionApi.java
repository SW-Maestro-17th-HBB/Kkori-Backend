package com.aisw.kkori.session.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.session.dto.SessionTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Session", description = "음성 세션 — LiveKit 룸 오디오 연결 토큰 발급")
public interface SessionApi {

    @Operation(summary = "음성 세션 접속 토큰 발급",
            description = """
                    인증 유저에게 새 룸의 LiveKit 입장 토큰(JWT)과 서버 URL을 발급한다.
                    요청마다 새 roomName이 생성되며, 발행 권한은 마이크로 한정된다(카메라·화면공유·데이터 차단).
                    클라이언트는 livekitUrl에 livekitToken을 들고 접속해 오디오를 송수신한다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<SessionTokenResponse> issueToken(@Parameter(hidden = true) Long userId);
}
