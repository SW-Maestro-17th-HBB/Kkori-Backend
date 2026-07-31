package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.service.ResumeParsedService;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.service.SessionService;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 면접 세션 생성의 동시성 검증 (interview-session-creation.md 기능 1 검증 기준).
 *
 * <p>어느 스레드가 먼저 user 잠금을 얻을지 가정하지 않는다 — 잠금은 직렬화만 보장하고
 * 순서를 정하지 않으므로, 각 시나리오는 "두 유효 결과 중 하나로만 수렴"을 단언한다
 * (ConsentChangeConcurrencyTest와 동일 접근).
 */
class InterviewSessionConcurrencyTest extends InterviewSessionIntegrationTestSupport {

    private static final int ITERATIONS = 10;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private ResumeParsedService resumeParsedService;

    @Autowired
    private UserService userService;

    @Test
    @DisplayName("동일 유저의 동시 생성 두 건은 직렬 처리되어 non-terminal 세션이 정확히 1개 남는다")
    void concurrentCreatesLeaveSingleNonTerminalSession() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            long userId = saveUser("kakao-c-1-" + i);
            long resumeId = embeddedResume(userId);
            Runnable create = () -> {
                try {
                    sessionService.create(userId, new InterviewSessionCreateRequest(
                            resumeId, InterviewType.THIRTY_MIN, Position.BACKEND));
                } catch (BusinessException e) {
                    // 먼저 커밋한 쪽은 커밋~재확인 사이에 상대가 교체를 완료하면 S005로 끝난다
                    // (agent-dispatch.md 승계 재확인) — "마지막 생성이 유효"의 응답 계약화, 유효한 수렴 결과
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SESSION_SUPERSEDED);
                }
            };

            runConcurrently(create, create);

            List<InterviewSession> nonTerminal =
                    sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
            assertThat(nonTerminal).hasSize(1);
            // 두 건 모두 커밋된다(교체 정책) — 이 유저의 세션은 정확히 2행(먼저 커밋된 쪽 ABORTED + 나중 쪽 PENDING).
            // 응답은 나중 쪽 성공 + 먼저 쪽 {성공 | S005} 중 하나로 수렴한다
            Long userSessions = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM interview_session WHERE user_id = ?", Long.class, userId);
            assertThat(userSessions).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("생성과 재분석이 경합해도 무효 이력서를 참조하는 세션은 생기지 않는다 (R010 xor R013)")
    void createVersusReanalyzeConverges() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            long userId = saveUser("kakao-c-2-" + i);
            long resumeId = embeddedResume(userId);
            AtomicReference<ErrorCode> createError = new AtomicReference<>();
            AtomicReference<ErrorCode> reanalyzeError = new AtomicReference<>();

            runConcurrently(
                    () -> {
                        try {
                            sessionService.create(userId, new InterviewSessionCreateRequest(
                                    resumeId, InterviewType.THIRTY_MIN, Position.BACKEND));
                        } catch (BusinessException e) {
                            createError.set(e.getErrorCode());
                        }
                    },
                    () -> {
                        try {
                            resumeParsedService.reanalyze(userId, resumeId);
                        } catch (BusinessException e) {
                            reanalyzeError.set(e.getErrorCode());
                        }
                    });

            boolean sessionReferencing = sessionRepository
                    .existsByResumeIdAndStatusIn(resumeId, SessionStatus.NON_TERMINAL);
            AnalysisStatus parseStatus = statusRepository.findByResumeId(resumeId)
                    .orElseThrow().getParseStatus();

            if (createError.get() == null) {
                // 생성 선점 — 재분석은 사용 중 차단(R013), 이력서는 EMBEDDED 그대로
                assertThat(reanalyzeError.get()).isEqualTo(ErrorCode.RESUME_IN_USE);
                assertThat(sessionReferencing).isTrue();
                assertThat(parseStatus).isEqualTo(AnalysisStatus.EMBEDDED);
            } else {
                // 재분석 선점 — 생성은 분석 진행 중(R010), 이 이력서를 참조하는 non-terminal 세션 없음
                assertThat(createError.get()).isEqualTo(ErrorCode.RESUME_ANALYSIS_IN_PROGRESS);
                assertThat(reanalyzeError.get()).isNull();
                assertThat(sessionReferencing).isFalse();
            }
            // 어느 순서든 불변식: non-terminal 세션이 참조하는 이력서는 EMBEDDED다
            if (sessionReferencing) {
                assertThat(parseStatus).isEqualTo(AnalysisStatus.EMBEDDED);
            }
        }
    }

    @Test
    @DisplayName("생성과 탈퇴가 경합하면 {탈퇴 선점 → 401·세션 없음} 또는 {생성 선점 → 세션 잔존} 중 하나로만 수렴한다")
    void createVersusWithdrawConverges() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            long userId = saveUser("kakao-c-3-" + i);
            long resumeId = embeddedResume(userId);
            AtomicReference<ErrorCode> createError = new AtomicReference<>();

            runConcurrently(
                    () -> {
                        try {
                            sessionService.create(userId, new InterviewSessionCreateRequest(
                                    resumeId, InterviewType.THIRTY_MIN, Position.BACKEND));
                        } catch (BusinessException e) {
                            createError.set(e.getErrorCode());
                        }
                    },
                    () -> userService.withdraw(userId));

            assertThat(userRepository.findById(userId).orElseThrow().isDeleted()).isTrue();
            List<InterviewSession> nonTerminal =
                    sessionRepository.findByUserIdAndStatusIn(userId, SessionStatus.NON_TERMINAL);
            if (createError.get() != null) {
                // 탈퇴 선점 — 잠금 후 활성 재확인이 거부, 세션 없음
                assertThat(createError.get()).isEqualTo(ErrorCode.UNAUTHORIZED);
                assertThat(nonTerminal).isEmpty();
            } else {
                // 생성 선점 — 세션은 남는다. 잔존 세션 정리는 E1 연계(후속 스토리) 소관이며
                // 그때까지 JWT 필터가 해당 유저의 접근 자체를 차단한다 (PRD 동시성 계약)
                assertThat(nonTerminal).hasSize(1);
            }
        }
    }

    @Test
    @DisplayName("탈퇴가 완료된 유저의 생성 요청은 잠금 후 재확인에서 401로 거부된다 (결정적 케이스)")
    void creationAfterWithdrawalIsRejected() {
        long userId = saveUser("kakao-c-4");
        long resumeId = embeddedResume(userId);
        userService.withdraw(userId);

        assertThatThrownBy(() -> sessionService.create(userId,
                new InterviewSessionCreateRequest(resumeId, InterviewType.THIRTY_MIN, Position.BACKEND)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThat(sessionRepository.count()).isZero();
    }

}
