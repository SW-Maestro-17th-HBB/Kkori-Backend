package com.aisw.kkori.user.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.user.api.UserApi;
import com.aisw.kkori.user.dto.UpdateUserRequest;
import com.aisw.kkori.user.dto.UserInfoResponse;
import com.aisw.kkori.user.dto.WithdrawResponse;
import com.aisw.kkori.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 API 엔드포인트. 요청·응답 명세와 문서화는 {@link UserApi}에 있고,
 * 검증·탈퇴 트랜잭션은 {@link UserService}에 위임해 컨트롤러를 얇게 유지한다.
 */
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController implements UserApi {

    private final UserService userService;

    /** 내 정보 조회. */
    @Override
    @GetMapping
    public ApiResponse<UserInfoResponse> getMyInfo(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userService.getMyInfo(userId));
    }

    /** 내 정보 수정 — name만 수정 가능하다. */
    @Override
    @PatchMapping
    public ApiResponse<UserInfoResponse> update(@AuthenticationPrincipal Long userId,
                                                @RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.updateName(userId, request.name()));
    }

    /** 회원 탈퇴 — soft delete 후 파기 예정 시각을 반환한다. */
    @Override
    @DeleteMapping
    public ApiResponse<WithdrawResponse> withdraw(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(userService.withdraw(userId));
    }
}
