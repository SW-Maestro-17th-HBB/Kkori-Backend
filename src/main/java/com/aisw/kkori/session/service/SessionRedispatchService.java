package com.aisw.kkori.session.service;

import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.StructuredData;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.RoomPresence;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 에이전트 재디스패치 파이프라인 (PRD interview-session-reconnection.md 기능 3).
 *
 * <p>판별 ③({@code AGENT_LOST} 전이) 커밋 직후 1회 호출된다 — 실시간 경로 전용이며 stale
 * 회수 경로(즉시 ABORTED)는 재디스패치하지 않는다. 순서는 단일성 계약(1차 — Spring 순서
 * 보장)이다:
 *
 * <ol>
 * <li><b>AGENT 사전 확인</b> (CAS보다 먼저): 관측 시 CAS·삭제·생성 없이 관측 기반 복원 —
 *     삭제 전 관측이라 "종료 중인 구 잡" 모호성이 없고, {@code redispatched_at}은 null로 남아
 *     복원된 에이전트의 후속 소실이 온전한 재디스패치 기회를 가진다. webhook 재전달에 복원을
 *     맡기지 않는 이유: 최종 유실 시 동작 중인 에이전트·룸을 유예 만료가 ABORTED로 오정리한다.</li>
 * <li><b>CAS</b>: 부재일 때만 재디스패치 권한 획득(at-most-once — 마커 커밋과 LiveKit 호출
 *     사이 프로세스 다운의 미실행 창은 감수, 유예 만료가 수렴). metadata 재조립 입력도 같은
 *     트랜잭션에서 확보한다 — RESUME_IN_USE 차단이 유지되어 재조립 결과는 최초와 동일하다.</li>
 * <li><b>기존 dispatch 정리</b>: 공집합이면 생략, 조회·삭제 실패는 생성 포기(동시 잡 방지 우선).</li>
 * <li><b>AGENT 부재 재확인</b>: 삭제 직후 관측은 종료 중인 구 잡일 수 있어 복원 증거로 쓰지
 *     않는다 — 복원·생성 없이 포기(유예 만료 수렴, 극소 조기 ABORTED 감수).</li>
 * <li><b>생성 직전 상태 재확인</b>: user 잠금은 CAS까지만 직렬화한다 — /end가 ABORTED·룸
 *     삭제를 끝냈으면 createDispatch의 룸 자동 생성이 terminal 좀비 룸을 만든다(승계 재확인
 *     패턴). 재확인~create 사이 극소 창은 자기 제한 소멸로 감수.</li>
 * <li><b>생성</b>: 동일 계약 — {@code createDispatch(룸, "kkori-interviewer", metadata, JRP_NEVER)}.</li>
 * </ol>
 *
 * <p>어느 단계의 실패든 로그만 남기고 끝낸다(무재시도·에러 응답 없음 — 사용자 요청 경로가
 * 아니다). 세션 수렴은 유예 만료가 보장하고, 부재 재확인 이후 구 잡이 재연결하는 잔여 창은
 * 에이전트 owner 검사(2차)가 완화한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRedispatchService {

    private final InterviewSessionRepository sessionRepository;
    private final ResumeRepositoryService resumeRepositoryService;
    private final DispatchMetadataAssembler metadataAssembler;
    private final SessionAgentDispatcher agentDispatcher;
    private final SessionRoomManager roomManager;
    private final SessionTransitionExecutor transitionExecutor;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 판별 ③ 커밋 직후 1회 — 실패를 밖으로 내지 않는다(webhook 처리 결과와 무관, 유예가 수렴 보장). */
    public void attemptRecovery(InterviewSession session) {
        try {
            run(session);
        } catch (RuntimeException e) {
            log.warn("재디스패치 파이프라인 실패 — 유예 만료 수렴 대기 (sessionId={}): {}",
                    session.getId(), e.getClass().getSimpleName());
        }
    }

    private void run(InterviewSession session) {
        long sessionId = session.getId();
        String room = session.getLivekitRoom();
        String candidate = CandidateIdentity.of(sessionId);

        // ⓪ AGENT 사전 확인 — CAS보다 먼저
        RoomPresence before = roomManager.probeRoomPresence(room, candidate);
        if (!before.observed()) {
            log.info("재디스패치 사전 확인 실패 — 시도 중단 (sessionId={})", sessionId);
            return;
        }
        if (before.agentPresent()) {
            restoreFromObservation(session, before);
            return;
        }

        // ① CAS + metadata 재조립 입력 확보
        String metadata = transactionTemplate.execute(status -> {
            userRepository.findWithLockById(session.getUserId());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (sessionRepository.claimRedispatch(sessionId, now) != 1) {
                return null;
            }
            StructuredData structuredData = session.getResumeId() == null ? null
                    : resumeRepositoryService.findById(session.getResumeId())
                            .map(Resume::getStructuredData).orElse(null);
            return metadataAssembler.assemble(
                    sessionId, session.getInterviewType(), session.getPosition(), structuredData);
        });
        if (metadata == null) {
            log.info("재디스패치 CAS 미획득 — 기시도·상태 변경 (sessionId={})", sessionId);
            return;
        }

        // ② 기존 dispatch 정리 (공집합이면 생략)
        List<String> existing = agentDispatcher.listDispatchIds(room);
        for (String dispatchId : existing) {
            agentDispatcher.deleteDispatch(room, dispatchId);
        }

        // ③ AGENT 부재 재확인 — 관측 시 복원 없이 포기(종료 중 구 잡 모호성)
        RoomPresence after = roomManager.probeRoomPresence(room, candidate);
        if (!after.observed()) {
            log.info("재디스패치 부재 재확인 실패 — 생성 포기 (sessionId={})", sessionId);
            return;
        }
        if (after.agentPresent()) {
            log.info("재디스패치 부재 재확인 — AGENT 재출현, 복구 포기(유예 수렴 대기) (sessionId={})", sessionId);
            return;
        }

        // ④ 생성 직전 상태 재확인
        if (!sessionRepository.existsByIdAndStatus(sessionId, SessionStatus.AGENT_LOST)) {
            log.info("재디스패치 상태 재확인 — AGENT_LOST 아님, 생성 중단 (sessionId={})", sessionId);
            return;
        }

        // ⑤ 생성 — 동일 계약(agent_name·metadata 4필드·JRP_NEVER)
        agentDispatcher.dispatch(room, metadata);
        log.info("재디스패치 완료 — joined(agent) 대조 복귀 대기 (sessionId={}, 기존 dispatch 정리 {}건)",
                sessionId, existing.size());
    }

    /** 사전 확인의 관측 기반 복원 — joined(agent) 매핑과 같은 분기 (CAS 미소진). */
    private void restoreFromObservation(InterviewSession session, RoomPresence presence) {
        if (presence.candidatePresent()) {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeAgentLostToActive(session.getId(), now));
            if (updated == 1) {
                log.info("재디스패치 사전 확인 — 구 에이전트 재연결·candidate 재실, ACTIVE 복원 (sessionId={})",
                        session.getId());
            }
        } else {
            int updated = transitionExecutor.execute(session.getUserId(),
                    now -> sessionRepository.resumeAgentLostToInterrupted(session.getId(), now));
            if (updated == 1) {
                log.info("재디스패치 사전 확인 — AGENT만 관측, INTERRUPTED 전환(잔여 창) (sessionId={})",
                        session.getId());
            }
        }
    }
}
