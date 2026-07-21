package com.aisw.kkori.session.dto;

/**
 * 음성 세션 접속 토큰 발급 응답.
 *
 * <p>필드명은 후속 면접 세션 엔드포인트와 동일하게 맞춰(이 엔드포인트의 확장 대상),
 * 클라이언트가 {@code livekitUrl}에 {@code livekitToken}을 들고 접속하면 된다.
 * {@code livekitRoom}은 클라이언트 참고용이며, 실제 룸은 첫 참가자 접속 시 LiveKit이 생성한다.
 */
public record SessionTokenResponse(
        String livekitToken,
        String livekitUrl,
        String livekitRoom
) {
}
