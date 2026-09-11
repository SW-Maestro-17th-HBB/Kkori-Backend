package com.aisw.kkori.global.oauth;

/**
 * 카카오 연결 끊기 실패 — 네트워크 오류·5xx·어드민 키 오류(-401) 등 재시도로 해소될 수 있는 실패.
 * 파기 배치가 건을 {@code FAILED}로 전환해 다음 회차에 다시 호출한다(PRD deletion.md 기능 4).
 */
public class KakaoUnlinkException extends RuntimeException {

    public KakaoUnlinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
