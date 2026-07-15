package com.aisw.kkori.global.config;

import com.aisw.kkori.global.jwt.JwtAuthenticationEntryPoint;
import com.aisw.kkori.global.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * JWT 기반 무상태 인증 설정.
 *
 * <p>인증 불필요 경로는 소셜 로그인 진입 3종(kakao·signup·reissue), 카카오 연결 해제
 * 웹훅(어드민 키로 자체 검증), Swagger 문서뿐이며, 나머지는 전부 Bearer AT가 필요하다.
 * logout은 AT 유저의 RT 소유 확인이 필요하므로 permitAll이 아니다(PRD).
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    /** 무상태(STATELESS) 체인 — 세션·폼로그인 없이 JWT 필터가 인증을 전담한다. */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/kakao", "/api/v1/auth/signup", "/api/v1/auth/reissue")
                        .permitAll()
                        // 와일드카드가 아닌 정확 경로만 — 자체 검증 없는 다른 웹훅이 딸려 열리는 사고 방지
                        .requestMatchers("/api/v1/webhook/kakao/unlink").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 프론트(React)가 다른 오리진에서 Authorization 헤더를 실어 호출하므로 CORS 허용이 필요하다.
     * 인증은 Bearer 헤더 방식이라 쿠키 자격증명(allowCredentials)은 열지 않는다.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
