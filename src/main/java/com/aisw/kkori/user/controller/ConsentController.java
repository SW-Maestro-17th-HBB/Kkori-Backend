package com.aisw.kkori.user.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.user.api.ConsentApi;
import com.aisw.kkori.user.dto.ConsentCatalogResponse;
import com.aisw.kkori.user.dto.ConsentChangeRequest;
import com.aisw.kkori.user.dto.UserConsentsResponse;
import com.aisw.kkori.user.service.ConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 두 경로 루트를 다루므로 클래스 레벨 매핑 없이 메서드별 절대 경로를 쓴다 —
 * {@code /api/v1/consents}(공개 메타데이터, 유저 소유 아님)와
 * {@code /api/v1/user/consents}(유저 종속 상태). 경로 구분 기준은 PRD consent.md 기능 2.
 */
@RestController
@RequiredArgsConstructor
public class ConsentController implements ConsentApi {

    private final ConsentService consentService;

    @Override
    @GetMapping("/api/v1/consents")
    public ResponseEntity<ApiResponse<ConsentCatalogResponse>> getCatalog() {
        // no-store: 버전 대조의 원천이라 캐시가 구버전을 재사용하면 409 후 재조회도 같은 구버전을
        // 받아 재시도 루프에 빠진다(PRD 기능 2). Security 기본 캐시 헤더는 앱이 Cache-Control을
        // 직접 설정하면 쓰이지 않으므로(CacheControlHeadersWriter) 이 값이 응답에 실린다.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(consentService.getCatalog()));
    }

    @Override
    @GetMapping("/api/v1/user/consents")
    public ApiResponse<UserConsentsResponse> getMyConsents(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(consentService.getMyConsents(userId));
    }

    @Override
    @PutMapping("/api/v1/user/consents/{type}")
    public ApiResponse<UserConsentsResponse> change(@AuthenticationPrincipal Long userId,
            @PathVariable String type, @Valid @RequestBody ConsentChangeRequest request) {
        return ApiResponse.success(consentService.change(userId, type, request));
    }
}
