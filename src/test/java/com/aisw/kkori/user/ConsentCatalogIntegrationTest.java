package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 현재 동의 항목·버전 제공 검증 (PRD {@code docs/requirements/user/consent.md} 기능 2).
 * 설정 버전 변경의 반영은 {@code ConsentVersionOverrideTest}가 다룬다.
 */
class ConsentCatalogIntegrationTest extends AuthIntegrationTestSupport {

    private static final String CATALOG_URI = "/api/v1/consents";

    @Test
    @DisplayName("인증 없이 4항목이 type·required·현재 버전과 함께 enum 순서로 반환된다")
    void catalogReturnsAllTypesWithoutAuth() throws Exception {
        mockMvc.perform(get(CATALOG_URI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.consents.length()").value(4))
                .andExpect(jsonPath("$.data.consents[0].type").value("privacy"))
                .andExpect(jsonPath("$.data.consents[0].required").value(true))
                .andExpect(jsonPath("$.data.consents[0].version").value(1))
                .andExpect(jsonPath("$.data.consents[1].type").value("audio_usage"))
                .andExpect(jsonPath("$.data.consents[1].required").value(true))
                .andExpect(jsonPath("$.data.consents[2].type").value("resume_usage"))
                .andExpect(jsonPath("$.data.consents[2].required").value(true))
                .andExpect(jsonPath("$.data.consents[3].type").value("marketing"))
                .andExpect(jsonPath("$.data.consents[3].required").value(false))
                .andExpect(jsonPath("$.data.consents[3].version").value(1));
    }

    @Test
    @DisplayName("응답 Cache-Control이 정확히 no-store다 — Security 기본 캐시 헤더가 아닌 컨트롤러 설정값이 실린다")
    void catalogIsNotCacheable() throws Exception {
        // 버전 대조의 원천이 캐시되면 409 후 재조회도 같은 구버전을 받아 재시도 루프에 빠진다(PRD 기능 2)
        mockMvc.perform(get(CATALOG_URI))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
}
