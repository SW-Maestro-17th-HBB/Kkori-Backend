package com.aisw.kkori.global.jwt;

import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.global.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 미인증 요청의 401 응답 작성.
 *
 * <p>필터 계층의 인증 실패는 {@code GlobalExceptionHandler}(MVC 계층)에 도달하지 않으므로,
 * 공통 envelope({@code ApiResponse})을 여기서 직접 직렬화해 응답 형식을 통일한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(ErrorResponse.of(ErrorCode.UNAUTHORIZED)));
    }
}
