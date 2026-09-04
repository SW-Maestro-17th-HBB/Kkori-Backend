package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.service.SessionEventService;
import com.aisw.kkori.session.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * webhook 전이와 세션 생성의 동시성 검증 (PRD interview-session-completion.md 기능 1 검증 기준).
 *
 * <p>두 경로가 user 행 잠금을 공유해 직렬화되므로, 생성 경로의 "교체 건수 불일치 방어선"이
 * 발동하지 않고 두 유효 결과 중 하나로만 수렴해야 한다(기존 동시성 테스트와 동일 접근 —
 * 잠금은 순서를 정하지 않는다).
 */
class SessionCompletionConcurrencyTest extends SessionCompletionTestSupport {

    private static final int ITERATIONS = 10;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionEventService eventService;

    @Test
    @DisplayName("PENDING 전이(webhook)와 생성 교체가 경합해도 non-terminal 세션은 정확히 1개로 수렴한다")
    void webhookActivateVersusCreateConverges() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            long userId = saveUser("kakao-cc-" + i);
            long resumeId = embeddedResume(userId);
            String room = "room-cc-" + i;
            long oldSessionId = sessionInStatus(userId, null, SessionStatus.PENDING, room);

            Runnable activate = () -> eventService.handle(
                    new SessionWebhookSignal(SessionWebhookSignal.Type.AGENT_JOINED, room, "participant_joined"));
            Runnable create = () -> {
                try {
                    sessionService.create(userId, new InterviewSessionCreateRequest(
                            resumeId, InterviewType.THIRTY_MIN, Position.BACKEND));
                } catch (BusinessException e) {
                    // webhook이 선점하면 ACTIVE 관측 → S003, 커밋~재확인 사이 교체는 S005 — 둘 다 유효한 수렴
                    assertThat(e.getErrorCode()).isIn(
                            ErrorCode.SESSION_ALREADY_IN_PROGRESS, ErrorCode.SESSION_SUPERSEDED);
                }
            };

            runConcurrently(activate, create);

            List<InterviewSession> nonTerminal =
                    sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
            assertThat(nonTerminal).hasSize(1);
            // 기존 세션은 {webhook 선점 → ACTIVE 유지·생성 거부} 또는 {교체 선점 → ABORTED·전이 no-op}
            // 둘 중 하나다 — ACTIVE와 신규 PENDING이 공존하는 결과는 존재하지 않는다
            String oldStatus = statusOfSession(oldSessionId);
            assertThat(oldStatus).isIn("ACTIVE", "ABORTED");
            if (oldStatus.equals("ACTIVE")) {
                assertThat(nonTerminal.get(0).getId()).isEqualTo(oldSessionId);
            }
        }
    }
}
