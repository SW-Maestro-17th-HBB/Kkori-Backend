package com.aisw.kkori.global.oauth;

/**
 * 카카오 연결 끊기 실패 — 네트워크 오류·5xx·어드민 키 오류(-401) 등 재시도로 해소될 수 있는 실패.
 * 파기 배치가 건을 {@code FAILED}로 전환해 다음 회차에 다시 호출한다(PRD deletion.md 기능 4).
 *
 * <p>원본 HTTP 예외를 원인(cause)으로 보존하지 않는다 — 그 메시지에는 응답 본문(회원번호 포함 가능)이 실리며,
 * 스택 트레이스 로깅 한 번으로 새어 나간다(공통: 로그·개인정보). 진단 재료는 고정 형식의 메시지뿐이다.
 */
public class KakaoUnlinkException extends RuntimeException {

    public KakaoUnlinkException(String message) {
        super(message);
    }
}
