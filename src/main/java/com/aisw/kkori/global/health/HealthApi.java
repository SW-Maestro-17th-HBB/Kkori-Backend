package com.aisw.kkori.global.health;

import com.aisw.kkori.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * ALB 헬스체크 명세. 인프라 전용 엔드포인트라 Swagger에 노출하지 않는다({@code @Hidden}).
 */
@Hidden
public interface HealthApi {

    ApiResponse<Void> check();
}
