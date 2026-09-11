package com.aisw.kkori.global.oauth;

/**
 * 카카오 연결 끊기(unlink) 포트 (PRD deletion.md 기능 4). 파기 배치가 탈퇴 시점 스냅샷 회원번호로 호출한다.
 * 로컬·테스트는 실제 카카오를 호출할 수 없어(어드민 키 더미) 테스트 더블로 대체하고, 실연동은 dev에서 확인한다.
 */
public interface KakaoUnlinkClient {

    /**
     * @return 연결을 끊었으면 {@link Outcome#UNLINKED}, 카카오가 "앱에 연결되지 않은 사용자"({@code -101})로
     *         응답하면(서비스 외부에서 이미 해제됨 등) {@link Outcome#ALREADY_UNLINKED} — 둘 다 완료로 취급한다
     * @throws KakaoUnlinkException 그 외 실패(네트워크·5xx·키 오류 등) — 재시도 대상
     */
    Outcome unlink(String providerId);

    enum Outcome { UNLINKED, ALREADY_UNLINKED }
}
