package com.aisw.kkori.session;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.oauth.KakaoOAuthProperties;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.InterviewSessionCreateRequest;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import com.aisw.kkori.session.service.SessionEventService;
import com.aisw.kkori.session.service.SessionService;
import com.aisw.kkori.user.dto.WithdrawResponse;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 시 진행 중 세션 즉시 종료 (PRD {@code docs/requirements/user/deletion.md} 기능 1 검증 기준).
 *
 * <p>룸 삭제는 커밋 후 실행기 스레드에서 일어나므로 {@code timeout}/{@code after}로 관측한다.
 * 탈퇴는 서비스 레벨로 호출한다 — HTTP 경로의 인증·응답 계약은 계정 테스트가 담당하고,
 * 여기서는 세션 전이와 룸 정리의 계약만 본다(웹훅 경로는 3초 예산 검증을 위해 HTTP로 호출).
 */
class SessionWithdrawalTerminationTest extends SessionCompletionTestSupport {

    private static final String WEBHOOK_URI = "/api/v1/webhook/kakao/unlink";
    private static final int CONCURRENCY_ITERATIONS = 5;

    @Autowired
    private UserService userService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionEventService sessionEventService;

    @Autowired
    private SessionRepositoryService sessionRepositoryService;

    @Autowired
    private KakaoOAuthProperties kakaoOAuthProperties;

    @ParameterizedTest(name = "{0} 세션")
    @EnumSource(value = SessionStatus.class, names = {"PENDING", "ACTIVE", "INTERRUPTED", "AGENT_LOST"})
    @DisplayName("non-terminal 세션은 탈퇴 트랜잭션 시각으로 ABORTED 선기록되고, 커밋 후 룸이 삭제된다")
    void abortsNonTerminalSessionOnWithdrawal(SessionStatus status) {
        String room = "room-wd-" + status.name().toLowerCase();
        long userId = saveUser("kakao-wd-" + status.name().toLowerCase());
        long sessionId = sessionInStatus(userId, null, status, room);

        userService.withdraw(userId);

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "ended_at")).isEqualTo(userDeletedAt(userId));
        verify(roomManager, timeout(5_000)).deleteRoomQuietly(room);
    }

    @ParameterizedTest(name = "{0} 세션")
    @EnumSource(value = SessionStatus.class, names = {"ENDED", "ABORTED"})
    @DisplayName("terminal 세션은 변경되지 않고 룸 삭제도 호출되지 않는다")
    void leavesTerminalSessionsUntouched(SessionStatus status) {
        long userId = saveUser("kakao-wd-term-" + status.name().toLowerCase());
        long sessionId = sessionInStatus(userId, null, status, "room-wd-term-" + status.name().toLowerCase());
        Instant endedBefore = Instant.parse("2026-01-01T00:00:00Z");
        setSessionInstant(sessionId, "ended_at", endedBefore);

        userService.withdraw(userId);

        assertThat(statusOfSession(sessionId)).isEqualTo(status.name());
        assertThat(sessionInstant(sessionId, "ended_at")).isEqualTo(endedBefore);
        verify(roomManager, after(500).never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("이미 탈퇴된 유저의 재요청(조건부 UPDATE 0행)은 세션을 건드리지 않고 룸 삭제도 하지 않는다")
    void repeatedWithdrawalDoesNotTouchSessions() {
        long userId = saveUser("kakao-wd-idem");
        userService.withdraw(userId);
        // 탈퇴 이후 남은 세션(생성 선점 경합의 잔존을 흉내) — 0행 경로는 이를 건드리지 않는다
        long sessionId = sessionInStatus(userId, null, SessionStatus.PENDING, "room-wd-idem");

        userService.withdraw(userId);

        assertThat(statusOfSession(sessionId)).isEqualTo("PENDING");
        verify(roomManager, after(500).never()).deleteRoomQuietly(anyString());
    }

    @Test
    @DisplayName("룸 삭제가 예외를 던져도 탈퇴 결과·커밋에 영향이 없다")
    void roomDeletionFailureDoesNotAffectWithdrawal() {
        doThrow(new RuntimeException("boom")).when(roomManager).deleteRoomQuietly(anyString());
        long userId = saveUser("kakao-wd-fail");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-wd-fail");

        WithdrawResponse response = userService.withdraw(userId);

        assertThat(response).isNotNull();
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(userDeletedAt(userId)).isNotNull();
        verify(roomManager, timeout(5_000)).deleteRoomQuietly("room-wd-fail");
    }

    @Test
    @DisplayName("웹훅 경로는 룸 삭제가 지연되어도 200을 예산 내에 반환한다 — 삭제는 응답 스레드 밖")
    void webhookResponseIsNotDelayedByRoomDeletion() throws Exception {
        CountDownLatch deleting = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleting.countDown();
            Thread.sleep(3_000);
            return null;
        }).when(roomManager).deleteRoomQuietly(anyString());
        long userId = saveUser("kakao-wd-hook");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-wd-hook");

        long started = System.nanoTime();
        mockMvc.perform(post(WEBHOOK_URI)
                        .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoOAuthProperties.adminKey())
                        .param("app_id", kakaoOAuthProperties.appId())
                        .param("user_id", "kakao-wd-hook")
                        .param("referrer_type", "UNLINK_FROM_APPS"))
                .andExpect(status().isOk());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2_000);
        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(userDeletedAt(userId)).isNotNull();
        assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("탈퇴 후 도착하는 room_finished는 terminal no-op이다 — 행이 있어도 ENDED로 뒤집히지 않는다")
    void roomFinishedAfterWithdrawalIsNoOp() {
        long userId = saveUser("kakao-wd-rf");
        long sessionId = sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-wd-rf");
        userService.withdraw(userId);
        Instant endedAt = sessionInstant(sessionId, "ended_at");
        seedTranscript(sessionId);

        sessionEventService.handle(new SessionWebhookSignal(
                SessionWebhookSignal.Type.ROOM_FINISHED, "room-wd-rf", "room_finished"));

        assertThat(statusOfSession(sessionId)).isEqualTo("ABORTED");
        assertThat(sessionInstant(sessionId, "ended_at")).isEqualTo(endedAt);
    }

    @Test
    @DisplayName("세션 생성과 탈퇴가 동시에 실행돼도 탈퇴 유저 명의의 non-terminal 세션은 남지 않는다")
    void creationAndWithdrawalConvergeWithoutLeftoverSession() throws Exception {
        for (int i = 0; i < CONCURRENCY_ITERATIONS; i++) {
            long userId = saveUser("kakao-wd-cc-" + i);
            Runnable create = () -> {
                try {
                    sessionService.create(userId, new InterviewSessionCreateRequest(
                            null, InterviewType.FIVE_MIN, Position.BACKEND));
                } catch (BusinessException e) {
                    // 탈퇴 선점이면 401, 생성 선점 후 커밋~재확인 사이에 탈퇴가 교체하면 S005 — 둘 다 유효한 수렴
                    assertThat(e.getErrorCode()).isIn(ErrorCode.UNAUTHORIZED, ErrorCode.SESSION_SUPERSEDED);
                }
            };
            Runnable withdraw = () -> userService.withdraw(userId);

            runConcurrently(create, withdraw);

            assertThat(sessionRepositoryService.findNonTerminalByUserId(userId)).isEmpty();
            assertThat(userDeletedAt(userId)).isNotNull();
        }
    }

    @Test
    @DisplayName("탈퇴로 세션이 정리된 유저는 복구 후 새 세션을 만들 수 있다 (잔존 세션의 409 차단 없음)")
    void restoredUserCanCreateNewSession() {
        long userId = saveUser("kakao-wd-restore");
        sessionInStatus(userId, null, SessionStatus.ACTIVE, "room-wd-restore");
        userService.withdraw(userId);
        // 복구 흉내 — 재동의 경로 자체는 auth 테스트 소관, 여기서는 세션 잔존 여부만 본다
        jdbcTemplate.update("UPDATE users SET deleted_at = NULL WHERE id = ?", userId);

        sessionService.create(userId, new InterviewSessionCreateRequest(null, InterviewType.FIVE_MIN, Position.BACKEND));

        assertThat(sessionRepositoryService.findNonTerminalByUserId(userId)).hasSize(1);
    }

    private Instant userDeletedAt(long userId) {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, userId);
        return value == null ? null : value.toInstant();
    }
}
