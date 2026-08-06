package com.aisw.kkori.session.service;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.dto.TerminationMarker;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.repository.InterviewTranscriptReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * webhook 신호의 상태 전이 실행 (PRD interview-session-completion.md — 이벤트→전이 매핑).
 *
 * <p><b>terminal 확정 원칙</b>: 어떤 경로든 ENDED 기록 조건은 transcript 행 존재와 일치시킨다 —
 * 행이 있으면 ENDED, 없으면 ABORTED. 정상 경로에서는 행이 room_finished 생성 전에 항상
 * 커밋되어 있어(에이전트가 flush 후 룸을 삭제) 결과가 같고, webhook 순서 역전 병리에서
 * "transcript 없는 ENDED"가 구조적으로 불가능해진다.
 *
 * <p><b>동시성</b>: 모든 전이는 세션에서 역추적한 user 행 잠금을 선행한다(HBB1-18이 권장 계약으로
 * 예고한 것의 이행 — 생성 경로의 교체 건수 방어선이 발동하지 않는 계약의 완성). 단 활성 재확인은
 * 하지 않는다 — 전이는 유저 상태와 무관한 세션 수렴이 목적이라 탈퇴 유저의 잔존 세션도 전이시킨다.
 * 판별 증거(행·표식) 읽기는 잠금 트랜잭션 밖에서 한다 — 조회~기록 사이의 잔여 경합 창은 PRD가
 * 감수한 항목이고, 낡은 결정은 조건부 UPDATE의 상태 술어가 걸러낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionEventService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewTranscriptReader transcriptReader;
    private final TerminationMarkerReader markerReader;
    private final SessionRoomManager roomManager;
    private final SessionTransitionExecutor transitionExecutor;

    /** 판별 대상 상태 — PENDING 포함 이유: 입장 직후 소실은 joined/left가 역순 도착할 수 있다. */
    private static final Set<SessionStatus> LOSS_OBSERVABLE = Set.of(SessionStatus.PENDING, SessionStatus.ACTIVE);

    public void handle(SessionWebhookSignal signal) {
        if (signal.type() == SessionWebhookSignal.Type.IGNORE) {
            return;
        }
        Optional<InterviewSession> found = sessionRepository.findByLivekitRoom(signal.roomName());
        if (found.isEmpty()) {
            log.info("미등록 룸 webhook — no-op (event={}, room={})", signal.rawEvent(), signal.roomName());
            return;
        }
        InterviewSession session = found.get();
        if (session.getStatus().isTerminal()) {
            // 공통 가드의 단락 — PENDING 교체·fallback 삭제·구 토큰 재생성 룸 소멸의 후속 이벤트가 여기로 흡수된다
            log.debug("terminal 세션 webhook — no-op (event={}, sessionId={})", signal.rawEvent(), session.getId());
            return;
        }
        switch (signal.type()) {
            case AGENT_JOINED -> activate(session);
            case AGENT_LEFT -> arbitrateLoss(session, signal.rawEvent());
            case ROOM_FINISHED -> finishRoom(session);
            case IGNORE -> { }
        }
    }

    /** participant_joined(AGENT): PENDING → ACTIVE. 그 외 상태는 조건부 UPDATE가 no-op으로 거른다. */
    private void activate(InterviewSession session) {
        int updated = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.activate(session.getId(), now, now));
        if (updated == 1) {
            log.info("세션 ACTIVE 전환 — 에이전트 입장 (sessionId={})", session.getId());
        }
    }

    /** room_finished: 행 판별 terminal 확정. 룸은 이미 소멸했으므로 정리할 것이 없다. */
    private void finishRoom(InterviewSession session) {
        SessionStatus to = transcriptReader.exists(session.getId()) ? SessionStatus.ENDED : SessionStatus.ABORTED;
        int updated = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.finishFrom(session.getId(), SessionStatus.NON_TERMINAL, to, now));
        if (updated == 1) {
            log.info("세션 {} 확정 — room_finished (sessionId={})", to, session.getId());
        }
    }

    /**
     * participant_left·connection_aborted(AGENT): 판별 3-경로 (크로스 레포 계약 —
     * Kkori-AI interview-end.md §3). ①·②는 잔존 룸을 정리하고(에이전트 룸 삭제 실패 경로),
     * ③은 룸을 남긴다 — 재dispatch 여지 보존이 에이전트 측 설계 의도이며, 룸 소멸 수렴은
     * room_finished 매핑이, 잔존 수렴은 유예 스위퍼가 담당한다.
     */
    private void arbitrateLoss(InterviewSession session, String rawEvent) {
        long sessionId = session.getId();
        if (transcriptReader.exists(sessionId)) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.finishFrom(sessionId, LOSS_OBSERVABLE, SessionStatus.ENDED, now));
            if (updated == 1) {
                log.info("판별 ① 행 존재 → ENDED (sessionId={}, event={})", sessionId, rawEvent);
                roomManager.deleteRoomQuietly(session.getLivekitRoom());
            }
            return;
        }
        Optional<TerminationMarker> marker = markerReader.read(sessionId);
        if (marker.isPresent()) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.finishFrom(sessionId, LOSS_OBSERVABLE, SessionStatus.ABORTED, now));
            if (updated == 1) {
                log.info("판별 ② 표식 존재 → ABORTED (sessionId={}, event={}, cause={}, markedAt={})",
                        sessionId, rawEvent, marker.get().cause(), marker.get().markedAt());
                roomManager.deleteRoomQuietly(session.getLivekitRoom());
            }
            return;
        }
        int updated = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.markAgentLost(sessionId, now));
        if (updated == 1) {
            log.info("판별 ③ 증거 없음 → AGENT_LOST — 유예 후 정리 (sessionId={}, event={})", sessionId, rawEvent);
        }
    }

}
