package com.aisw.kkori.global.health;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ALB 헬스체크 엔드포인트 — 인증 없이 200을 돌려준다. */
class HealthEndpointTest extends AuthIntegrationTestSupport {

    @Test
    @DisplayName("GET /api/v1/health는 Bearer 토큰 없이 200 success를 응답한다")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
