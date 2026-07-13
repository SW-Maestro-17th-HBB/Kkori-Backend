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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;

    @Override
    @PostMapping("/kakao")
    public ApiResponse<KakaoLoginResponse> kakaoLogin(@RequestBody KakaoLoginRequest request) {
        return ApiResponse.success(authService.kakaoLogin(request.code()));
    }

    @Override
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenResponse> signup(@RequestBody SignupRequest request) {
        return ApiResponse.success(authService.signup(request));
    }

    @Override
    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@RequestBody ReissueRequest request) {
        return ApiResponse.success(authService.reissue(request.refreshToken()));
    }

    @Override
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody LogoutRequest request) {
        authService.logout(userId, request.refreshToken());
        return ApiResponse.success();
    }
}
