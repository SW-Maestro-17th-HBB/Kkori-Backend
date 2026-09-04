package com.aisw.kkori.auth.controller;

import com.aisw.kkori.auth.api.AuthApi;
import com.aisw.kkori.auth.dto.KakaoLoginRequest;
import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.LogoutRequest;
import com.aisw.kkori.auth.dto.ReissueRequest;
import com.aisw.kkori.auth.dto.SignupRequest;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.auth.service.AuthService;
import com.aisw.kkori.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API 엔드포인트. 요청·응답 명세와 문서화는 {@link AuthApi}에 있고,
 * 흐름 로직은 {@link AuthService}에 위임해 컨트롤러를 얇게 유지한다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;

    /** 카카오 로그인·회원가입 시작 — 인가 코드로 신규/기존/복구를 판정한다. */
    @Override
    @PostMapping("/kakao")
    public ApiResponse<KakaoLoginResponse> kakaoLogin(@RequestBody KakaoLoginRequest request) {
        return ApiResponse.success(authService.kakaoLogin(request.code()));
    }

    /** 회원가입 완료 — signup token과 동의 내역으로 계정을 생성하고 토큰 쌍을 발급한다. */
    @Override
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenResponse> signup(@RequestBody SignupRequest request) {
        return ApiResponse.success(authService.signup(request));
    }

    /** 토큰 재발급 — RTR로 기존 RT를 폐기하고 새 토큰 쌍을 발급한다. */
    @Override
    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@RequestBody ReissueRequest request) {
        return ApiResponse.success(authService.reissue(request.refreshToken()));
    }

    /** 로그아웃 — 본인 소유 RT를 폐기한다. 항상 200을 반환한다. */
    @Override
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody LogoutRequest request) {
        authService.logout(userId, request.refreshToken());
        return ApiResponse.success();
    }
}
