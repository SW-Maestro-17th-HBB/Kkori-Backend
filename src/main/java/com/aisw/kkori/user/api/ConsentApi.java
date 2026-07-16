package com.aisw.kkori.user.api;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.user.dto.ConsentCatalogResponse;
import com.aisw.kkori.user.dto.ConsentChangeRequest;
import com.aisw.kkori.user.dto.UserConsentsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Consent", description = "수집 동의 항목·상태 조회 및 선택 동의 변경")
public interface ConsentApi {

    @Operation(summary = "현재 동의 항목·버전 제공",
            description = """
                    동의 화면 구성에 필요한 검증 메타데이터(항목·필수 여부·현재 동의서 버전)를 반환한다.
                    가입 전 동의 화면에서도 호출되므로 인증이 필요 없다. 버전 대조의 원천이므로
                    응답은 캐시되지 않는다(Cache-Control: no-store) — 409 처리 시 캐시를 우회해 재조회할 것.""")
    ResponseEntity<ApiResponse<ConsentCatalogResponse>> getCatalog();

    @Operation(summary = "내 동의 상태 조회",
            description = """
                    전체 동의 항목의 최신 상태(agreed·기록 버전·마지막 변경 시각)를 반환한다.
                    이력이 없는 항목도 agreed=false, version·updatedAt=null로 포함된다.""",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<UserConsentsResponse> getMyConsents(@Parameter(hidden = true) Long userId);

    @Operation(summary = "선택 동의 변경",
            description = """
                    선택 항목(marketing)의 동의·철회를 기록하고 전체 최신 상태를 반환한다.
                    동의(agreed=true)는 사용자가 확인한 동의서 version이 필수이며 서버 현재 버전과
                    다르면 409(U005). 필수 항목 변경은 400(U004), 알 수 없는 항목은 400(U003).
                    동일 상태 재요청은 이력을 만들지 않는다(멱등, 200).""",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<UserConsentsResponse> change(@Parameter(hidden = true) Long userId,
            @Parameter(description = "동의 항목 — 소문자 스네이크 표기 (예: marketing)") String type,
            ConsentChangeRequest request);
}
