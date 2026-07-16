package com.aisw.kkori.user.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.user.dto.UpdateUserRequest;
import com.aisw.kkori.user.dto.UserInfoResponse;
import com.aisw.kkori.user.dto.WithdrawResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "User", description = "계정 정보 조회·수정·탈퇴")
public interface UserApi {

    @Operation(summary = "내 정보 조회",
            description = "인증된 사용자의 계정 정보를 반환한다. email·name은 카카오 제공 여부에 따라 null일 수 있다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<UserInfoResponse> getMyInfo(@Parameter(hidden = true) Long userId);

    @Operation(summary = "내 정보 수정",
            description = """
                    수정 가능한 필드는 name 하나다(앞뒤 공백 제거 후 1~100자, 코드 포인트 기준).
                    email 등 미지원 필드는 무시된다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<UserInfoResponse> update(@Parameter(hidden = true) Long userId, UpdateUserRequest request);

    @Operation(summary = "회원 탈퇴",
            description = """
                    계정을 즉시 접근 차단(soft delete)하고 파기 대기로 등록한다.
                    실제 개인정보 파기는 유예 기간(기본 3일, 설정으로 조정 가능) 후 배치가 수행하며,
                    그 전에 재로그인해 재동의하면 계정이 복구된다. 응답의 purgeScheduledAt이 파기 예정 시각이다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<WithdrawResponse> withdraw(@Parameter(hidden = true) Long userId);
}
