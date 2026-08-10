package com.aisw.kkori.session.service;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.RoomPresence;
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
 * webhook 신호의 상태 전이 실행 (PRD interview-session-completion.md ·
 * interview-session-reconnection.md — 이벤트→전이 매핑).
 *
 * <p><b>terminal 확정 원칙</b>: 어떤 경로든 ENDED 기록 조건은 transcript 행 존재와 일치시킨다 —
 * 행이 있으면 ENDED, 없으면 ABORTED. 정상 경로에서는 행이 room_finished 생성 전에 항상
 * 커밋되어 있어(에이전트가 flush 후 룸을 삭제) 결과가 같고, webhook 순서 역전 병리에서
 * "transcript 없는 ENDED"가 구조적으로 불가능해진다.
 *
 * <p><b>대조 공통 규칙 (HBB1-308)</b>: candidate 단독 관측은 ACTIVE의 증거가 아니다 — ACTIVE
 * 복원은 candidate + AGENT 동시 관측에서만 한다(에이전트 소실 webhook이 지연·유실 중일 수
 * 있어, 에이전트 없는 룸을 ACTIVE로 되살리는 병리 차단). joined 이벤트 자체도 복귀의 증거로
 * 삼지 않는다(지연·중복 전달, 고아 룸 재생성 입장 가능).
 *
 * <p><b>동시성</b>: 모든 전이는 세션에서 역추적한 user 행 잠금을 선행한다(HBB1-18이 권장 계약으로
 * 예고한 것의 이행 — 생성 경로의 교체 건수 방어선이 발동하지 않는 계약의 완성). 단 활성 재확인은
 * 하지 않는다 — 전이는 유저 상태와 무관한 세션 수렴이 목적이라 탈퇴 유저의 잔존 세션도 전이시킨다.
 * 판별 증거(행·표식)·대조(LiveKit) 읽기는 잠금 트랜잭션 밖에서 한다 — 조회~기록 사이의 잔여
 * 경합 창은 PRD가 감수한 항목이고, 낡은 결정은 조건부 UPDATE의 상태 술어가 걸러낸다.
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
    private final SessionRedispatchService redispatchService;

    /** 판별 대상 상태 — PENDING 포함: 입장 직후 소실은 joined/left가 역순 도착할 수 있다.
     * INTERRUPTED 포함(HBB1-308): 이탈 대기 중 에이전트 소실 교차곱. */
    private static final Set<SessionStatus> LOSS_OBSERVABLE =
            Set.of(SessionStatus.PENDING, SessionStatus.ACTIVE, SessionStatus.INTERRUPTED);

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
            case CANDIDATE_JOINED -> resumeIfInterrupted(session, signal.rawEvent());
            case CANDIDATE_LEFT -> handleCandidateLeft(session, signal.rawEvent());
            case ROOM_FINISHED -> finishRoom(session);
            case IGNORE -> { }
        }
    }

    /**
     * participant_joined(AGENT): PENDING → ACTIVE, AGENT_LOST는 대조 복귀(HBB1-308 — 재디스패치
     * 복귀 경로). 그 외 상태는 조건부 UPDATE가 no-op으로 거른다.
     */
    private void activate(InterviewSession session) {
        if (session.getStatus() == SessionStatus.AGENT_LOST) {
            recoverFromAgentLost(session);
            return;
        }
        int updated = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.activate(session.getId(), now, now));
        if (updated == 1) {
            log.info("세션 ACTIVE 전환 — 에이전트 입장 (sessionId={})", session.getId());
        }
    }

    /**
     * joined(agent) × AGENT_LOST — 룸 대조 후 복귀한다. <b>AGENT 실존 확인 선행</b>: 소실 전
     * joined의 지연·중복 전달이 에이전트 없는 룸을 살리는 가짜 복구를 차단한다(대조는 실시간
     * 관측이라 참가자 목록 1회로 AGENT·candidate를 함께 판정).
     */
    private void recoverFromAgentLost(InterviewSession session) {
        RoomPresence presence = roomManager.probeRoomPresence(
                session.getLivekitRoom(), CandidateIdentity.of(session.getId()));
        if (!presence.observed()) {
            throw new IllegalStateException(
                    "복귀 대조 실패 — webhook 재전송 유도 (sessionId=%d)".formatted(session.getId()));
        }
        if (!presence.agentPresent()) {
            log.info("joined(agent) 관측이나 룸에 AGENT 부재 — 지연·중복 joined 흡수, no-op (sessionId={})",
                    session.getId());
            return;
        }
        if (presence.candidatePresent()) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeAgentLostToActive(session.getId(), now));
            if (updated == 1) {
                log.info("재디스패치 복귀 — candidate 재실, ACTIVE (sessionId={})", session.getId());
            }
        } else {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeAgentLostToInterrupted(session.getId(), now));
            if (updated == 1) {
                log.info("재디스패치 복귀 — candidate 부재, INTERRUPTED(잔여 창) (sessionId={})", session.getId());
            }
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
     * participant_left(candidate) — 매핑 확장 표의 상태별 분기 (재연결 PRD). 유령 퇴장
     * (DUPLICATE_IDENTITY)은 어댑터가 이미 IGNORE로 접었다.
     *
     * <p><b>잠금 전 관측 상태로 분기하지 않는다</b> — agent-left와의 경합(관측은 ACTIVE, 잠금
     * 시점엔 AGENT_LOST)에서 관측 기준 분기는 이탈 기록을 통째로 놓치고, `disconnected_at`이
     * 없으면 /rejoin이 S009로 거절되어 3분 재연결 계약이 깨진다. 대신 상태 술어를 단 조건부
     * UPDATE를 순차 시도한다 — 술어가 잠금 시점의 최신 상태로 분기하며, ACTIVE→INTERRUPTED가
     * 아니면 AGENT_LOST의 이탈 기록(first-wins)을 시도하고 그 외(INTERRUPTED 중복·PENDING·
     * terminal)는 둘 다 no-op이다.
     */
    private void handleCandidateLeft(InterviewSession session, String rawEvent) {
        int interrupted = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.interrupt(session.getId(), now));
        if (interrupted == 1) {
            log.info("candidate 이탈 — INTERRUPTED 전이 (sessionId={}, event={})",
                    session.getId(), rawEvent);
            // [커밋 후] 즉시 대조 — reason 미실림 시 가짜 INTERRUPTED를 왕복 1회 안에 보정
            restoreIfBothPresent(session, "즉시 대조");
            return;
        }
        int recorded = transitionExecutor.execute(session.getUserId(),
                now -> sessionRepository.recordDisconnectedIfAbsent(session.getId(), now));
        if (recorded == 1) {
            log.info("AGENT_LOST 중 candidate 이탈 — disconnected_at 기록 (sessionId={})",
                    session.getId());
        }
    }

    /**
     * participant_joined(candidate) — INTERRUPTED에서만 룸 대조 후 복귀한다. joined 이벤트
     * 자체를 복귀의 증거로 삼지 않는다(대조 공통 규칙). AGENT_LOST는 no-op — 에이전트 없는
     * ACTIVE를 만들지 않으며, 이후 joined(agent) 대조가 candidate를 관측해 수렴시킨다.
     */
    private void resumeIfInterrupted(InterviewSession session, String rawEvent) {
        if (session.getStatus() != SessionStatus.INTERRUPTED) {
            return;
        }
        RoomPresence presence = roomManager.probeRoomPresence(
                session.getLivekitRoom(), CandidateIdentity.of(session.getId()));
        if (!presence.observed()) {
            // 500으로 끝내 LiveKit 재전송을 유도한다 — 전이는 멱등이라 재수신이 안전하다
            throw new IllegalStateException(
                    "복귀 대조 실패 — webhook 재전송 유도 (sessionId=%d)".formatted(session.getId()));
        }
        if (presence.bothPresent()) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeFromInterrupted(session.getId(), now));
            if (updated == 1) {
                log.info("candidate 재입장 — ACTIVE 복귀 (sessionId={}, event={})", session.getId(), rawEvent);
            }
        } else {
            log.info("복귀 joined 관측이나 대조 미충족 — INTERRUPTED 유지 (sessionId={}, agent={}, candidate={})",
                    session.getId(), presence.agentPresent(), presence.candidatePresent());
        }
    }

    /** 대조 기반 ACTIVE 복원 — candidate+AGENT 동시 관측에서만. 대조 실패는 무시(스위퍼가 담당). */
    private void restoreIfBothPresent(InterviewSession session, String path) {
        RoomPresence presence = roomManager.probeRoomPresence(
                session.getLivekitRoom(), CandidateIdentity.of(session.getId()));
        if (presence.bothPresent()) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeFromInterrupted(session.getId(), now));
            if (updated == 1) {
                log.info("{} — candidate 실존, ACTIVE 복원(가짜 INTERRUPTED 보정) (sessionId={})",
                        path, session.getId());
            }
        }
    }

    /**
     * participant_left·connection_aborted(AGENT): 판별 3-경로 (크로스 레포 계약 —
     * Kkori-AI interview-end.md §3). ①·②는 잔존 룸을 정리하고(에이전트 룸 삭제 실패 경로),
     * ③은 룸을 남긴다 — 재dispatch 여지 보존이 에이전트 측 설계 의도이며, 룸 소멸 수렴은
     * room_finished 매핑이, 잔존 수렴은 유예 스위퍼가 담당한다. ③ 전이는 disconnectedAt을
     * 보존한다(INTERRUPTED발 — 재연결 deadline·기발급 재입장 토큰의 앵커).
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
            log.info("판별 ③ 증거 없음 → AGENT_LOST — 재디스패치 시도 후 유예 수렴 (sessionId={}, event={})",
                    sessionId, rawEvent);
            // [커밋 후] 재디스패치 — 실시간 판별 ③ 전용(stale 회수는 즉시 ABORTED라 트리거 없음).
            // 실패는 파이프라인이 삼킨다 — webhook 처리 결과에 영향 없음, 수렴은 유예 만료 보장.
            redispatchService.attemptRecovery(session);
        }
    }

}
