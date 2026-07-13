package com.aisw.kkori.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi(Swagger UI) 설정.
 *
 * <p>Swagger UI: {@code /swagger-ui.html}, OpenAPI 문서: {@code /v3/api-docs}.
 * SecurityConfig가 현재 모든 요청을 permitAll 하므로 별도 경로 허용은 불필요하다.
 * 인가 규칙을 도입하면 위 두 경로를 permitAll에 추가할 것.
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI kkoriOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kkori API")
                        .description("AI 면접 준비 서비스 Kkori 백엔드 API 문서")
                        .version("v0.0.1"));
    }
}
