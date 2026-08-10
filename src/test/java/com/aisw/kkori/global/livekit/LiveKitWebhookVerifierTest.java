package com.aisw.kkori.global.livekit;

import com.aisw.kkori.LiveKitWebhookTestSigner;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.session.dto.SessionWebhookSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * webhook 서명 검증·이벤트 변환 검증 (PRD interview-session-completion.md 기능 1).
 *
 * <p>서명 요청은 LiveKit 발신 형식대로 직접 구성한다 — Authorization 헤더는 API Secret으로
 * 서명한 JWT(issuer=API Key, {@code sha256} claim=바디 SHA-256 base64)다. Cloud webhook은
 * 공인 URL이 필요해 실이벤트 자동화가 불가하므로 이 구성 검증이 자동 테스트를 담당한다.
 */
class LiveKitWebhookVerifierTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-at-least-thirty-two-bytes-long";

    private final LiveKitWebhookVerifier verifier = new LiveKitWebhookVerifier(new LiveKitProperties(
            "wss://test.invalid", API_KEY, API_SECRET, Duration.ofHours(1), Duration.ofSeconds(3)));

    @ParameterizedTest(name = "{0}(kind={1}) → {2}")
    @DisplayName("이벤트·participant kind가 도메인 신호로 매핑된다")
    @CsvSource({
            "participant_joined, AGENT, AGENT_JOINED",
            "participant_joined, STANDARD, CANDIDATE_JOINED",
            "participant_left, AGENT, AGENT_LEFT",
            "participant_left, STANDARD, CANDIDATE_LEFT",
            "participant_connection_aborted, AGENT, AGENT_LEFT",
            "participant_connection_aborted, STANDARD, IGNORE",
    })
    void mapsParticipantEvents(String event, String kind, SessionWebhookSignal.Type expected) {
        String body = participantBody(event, kind);

        SessionWebhookSignal signal = verifier.verify(body, sign(body, API_SECRET));

        assertThat(signal.type()).isEqualTo(expected);
        if (expected != SessionWebhookSignal.Type.IGNORE) {
            assertThat(signal.roomName()).isEqualTo("room-w");
        }
    }

    @ParameterizedTest(name = "reason={0} → {1}")
    @DisplayName("candidate left의 reason 가드 — DUPLICATE_IDENTITY(유령 퇴장)만 IGNORE, 그 외는 이탈 신호 (HBB1-308)")
    @CsvSource({
            "DUPLICATE_IDENTITY, IGNORE",
            "CLIENT_INITIATED, CANDIDATE_LEFT",
    })
    void candidateLeftReasonGuard(String reason, SessionWebhookSignal.Type expected) {
        String body = "{\"event\":\"participant_left\",\"room\":{\"name\":\"room-w\"},"
                + "\"participant\":{\"identity\":\"p\",\"kind\":\"STANDARD\",\"disconnectReason\":\"%s\"}}"
                .formatted(reason);

        assertThat(verifier.verify(body, sign(body, API_SECRET)).type()).isEqualTo(expected);
    }

    @Test
    @DisplayName("room_finished는 ROOM_FINISHED로, 미구독 이벤트는 IGNORE로 매핑된다")
    void mapsRoomEvents() {
        String finished = "{\"event\":\"room_finished\",\"room\":{\"name\":\"room-w\"}}";
        assertThat(verifier.verify(finished, sign(finished, API_SECRET)).type())
                .isEqualTo(SessionWebhookSignal.Type.ROOM_FINISHED);

        String track = "{\"event\":\"track_published\",\"room\":{\"name\":\"room-w\"}}";
        assertThat(verifier.verify(track, sign(track, API_SECRET)).type())
                .isEqualTo(SessionWebhookSignal.Type.IGNORE);
    }

    @Test
    @DisplayName("바디 변조는 sha256 불일치로 거부된다 (C005)")
    void rejectsTamperedBody() {
        String body = participantBody("participant_joined", "AGENT");
        String header = sign(body, API_SECRET);
        String tampered = body.replace("room-w", "room-x");

        assertThatThrownBy(() -> verifier.verify(tampered, header))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 Secret으로 서명된 요청은 거부된다 (C005)")
    void rejectsWrongSignature() {
        String body = participantBody("participant_joined", "AGENT");
        String header = sign(body, "another-secret-that-is-not-ours-32bytes!!");

        assertThatThrownBy(() -> verifier.verify(body, header))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Authorization 헤더 부재·빈 바디는 거부된다 (C005)")
    void rejectsMissingHeaderOrBody() {
        String body = participantBody("participant_joined", "AGENT");

        assertThatThrownBy(() -> verifier.verify(body, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> verifier.verify(null, sign(body, API_SECRET)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private static String participantBody(String event, String kind) {
        return "{\"event\":\"%s\",\"room\":{\"name\":\"room-w\"},\"participant\":{\"identity\":\"p\",\"kind\":\"%s\"}}"
                .formatted(event, kind);
    }

    private static String sign(String body, String secret) {
        return LiveKitWebhookTestSigner.sign(body, API_KEY, secret);
    }
}
