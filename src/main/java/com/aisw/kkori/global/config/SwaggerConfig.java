package com.aisw.kkori.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi(Swagger UI) 설정.
 *
 * <p>Swagger UI: {@code /swagger-ui.html}, OpenAPI 문서: {@code /v3/api-docs}.
 * 두 경로는 SecurityConfig에서 permitAll로 허용한다.
 * 인증 필요 API는 {@code @SecurityRequirement(name = "bearerAuth")}를 붙이면
 * Swagger UI의 Authorize 버튼으로 AT를 넣어 호출할 수 있다.
 */
@Configuration
public class SwaggerConfig {

    /** API 기본 정보와 bearerAuth 보안 스킴을 등록한다. */
    @Bean
    public OpenAPI kkoriOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kkori API")
                        .description("AI 면접 준비 서비스 Kkori 백엔드 API 문서")
                        .version("v0.0.1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
