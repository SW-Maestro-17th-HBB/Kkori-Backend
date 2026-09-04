package com.aisw.kkori.session.dto;

/**
 * 면접 세션 생성 응답. livekit* 필드명은 HBB1-256 계약을 계승하고 세션 식별자(id)가 추가됐다.
 */
public record InterviewSessionCreateResponse(
        Long id,
        String livekitToken,
        String livekitUrl,
        String livekitRoom
) {
}
