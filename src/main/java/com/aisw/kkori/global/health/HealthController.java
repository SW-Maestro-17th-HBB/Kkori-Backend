package com.aisw.kkori.global.health;

import com.aisw.kkori.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ALB 헬스체크 엔드포인트 — 앱 포트(8080)가 요청을 받을 수 있는지만 본다.
 *
 * <p>actuator health는 관리 포트(8081)에 있어서 8080 커넥터가 막혀도(스레드 고갈 등) UP을 돌려준다.
 * ALB는 트래픽을 보내는 포트 자체를 검사해야 하므로 여기서 200을 응답한다.
 * DB·Redis 상태는 일부러 보지 않는다 — 의존성 장애 때 태스크를 교체해도 해결되지 않고,
 * 그 상태는 actuator health(8081)로 확인한다. 인증 없이 열려 있다(SecurityConfig permitAll).
 */
@RestController
public class HealthController implements HealthApi {

    @Override
    @GetMapping("/api/v1/health")
    public ApiResponse<Void> check() {
        return ApiResponse.success();
    }
}
