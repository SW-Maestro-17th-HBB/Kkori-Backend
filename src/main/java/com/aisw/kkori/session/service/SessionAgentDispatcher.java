package com.aisw.kkori.session.service;

/**
 * 면접관 에이전트를 세션 룸에 명시 디스패치하는 추상. 실제 호출은 벤더 어댑터
 * ({@code global.livekit.LiveKitAgentDispatcher})가 담당한다 — 룸 준비({@link SessionRoomManager})·
 * 토큰 발급({@link SessionTicketIssuer})과 동일한 격리 구조.
 */
public interface SessionAgentDispatcher {

    /**
     * 에이전트 디스패치를 요청한다. 대상 에이전트(agent_name)는 크로스 레포 계약값이라
     * 어댑터가 상수로 확정하며, 도메인은 룸과 payload만 넘긴다.
     *
     * @param roomName 디스패치 대상 룸 이름
     * @param metadata 조립된 디스패치 metadata JSON({@code DispatchMetadataAssembler} 산출)
     * @throws com.aisw.kkori.global.exception.BusinessException 통신·타임아웃·응답 오류 등 어떤
     *         실패든 {@code SESSION_DISPATCH_FAILED}(S004)로 변환해 던진다 — 예외 매핑의 소유자는
     *         어댑터이며, 호출측은 보상(룸 삭제)만 하고 원예외를 그대로 재던진다
     */
    void dispatch(String roomName, String metadata);

    /**
     * 룸의 잔존 dispatch id 목록을 조회한다 — 재디스패치의 활성 dispatch 단일성 정리(HBB1-308).
     * 실패는 {@code SESSION_DISPATCH_FAILED}(S004)로 던진다 — 호출측(재디스패치 파이프라인)은
     * 생성을 포기한다(동시 잡 방지가 복원보다 우선).
     */
    java.util.List<String> listDispatchIds(String roomName);

    /**
     * 잔존 dispatch를 삭제한다. 실패는 {@code SESSION_DISPATCH_FAILED}(S004) — 삭제가 실패하면
     * 생성하지 않는다. 단 {@code DeleteDispatch}는 실행 중 잡의 종료 완료를 계약하지 않는다
     * (재디스패치 파이프라인이 부재 재확인으로 보완).
     */
    void deleteDispatch(String roomName, String dispatchId);
}
