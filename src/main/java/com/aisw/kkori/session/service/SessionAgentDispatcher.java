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
}
