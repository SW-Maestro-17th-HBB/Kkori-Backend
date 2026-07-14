package com.aisw.kkori.auth.api;

import com.aisw.kkori.auth.dto.KakaoLoginRequest;
import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.LogoutRequest;
import com.aisw.kkori.auth.dto.ReissueRequest;
import com.aisw.kkori.auth.dto.SignupRequest;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth", description = "카카오 소셜 로그인 · JWT 토큰 발급/재발급")
public interface AuthApi {

    @Operation(summary = "카카오 로그인·회원가입 시작",
            description = """
                    프론트가 카카오 콜백으로 받은 인가 코드를 전달하면 유저를 판정한다.
                    기존 유저는 즉시 토큰 쌍을, 신규 유저는 signupToken(10분)만 반환한다.
                    탈퇴 유예 기간 내 유저는 계정을 복구하고 isRestored=true와 함께 토큰 쌍을 반환한다.""")
    ApiResponse<KakaoLoginResponse> kakaoLogin(KakaoLoginRequest request);

    @Operation(summary = "회원가입 완료",
            description = """
                    signupToken과 동의 내역을 받아 계정을 생성하고 토큰 쌍을 발급한다.
                    필수 동의 3종(privacy·audio_usage·resume_usage)이 모두 agreed=true여야 한다.""")
    ApiResponse<TokenResponse> signup(SignupRequest request);

    @Operation(summary = "토큰 재발급",
            description = """
                    Refresh Token으로 새 토큰 쌍을 발급한다(RTR — 기존 RT는 폐기).
                    폐기된 RT 재사용은 60초 Grace Period 내 재시도만 허용하며, 초과 시
                    탈취로 간주해 해당 유저의 모든 RT를 무효화한다.""")
    ApiResponse<TokenResponse> reissue(ReissueRequest request);

    @Operation(summary = "로그아웃",
            description = "전달한 RT가 본인 소유일 때만 폐기한다. 멱등 연산으로 항상 200을 반환한다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<Void> logout(@Parameter(hidden = true) Long userId, LogoutRequest request);
}
