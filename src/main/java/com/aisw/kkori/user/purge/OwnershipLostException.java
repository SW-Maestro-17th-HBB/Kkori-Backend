package com.aisw.kkori.user.purge;

/**
 * 파기 선점 소유권 상실 — 다른 인스턴스가 stale 회수로 이 건을 재선점했다(PRD deletion.md 기능 2 펜싱).
 * 오케스트레이터는 이 예외를 받으면 실패 전환도 하지 않고 이번 인스턴스의 결과를 폐기한다(그쪽이 소유자).
 * 단계 구현체도 되돌릴 수 없는 외부 호출 직전의 소유권 재확인에서 던질 수 있다.
 */
public class OwnershipLostException extends RuntimeException {

    public OwnershipLostException() {
        super("purge ownership lost");
    }
}
