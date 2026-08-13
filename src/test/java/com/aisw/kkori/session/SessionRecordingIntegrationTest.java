package com.aisw.kkori.session;

import com.aisw.kkori.LiveKitWebhookTestSigner;
import com.aisw.kkori.global.livekit.LiveKitProperties;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.dto.AudioAnalysisRequestedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * egress_ended 웹훅 → 세션 기록·음성 분석 요청 발행 통합 검증
 * (docs/requirements/session/interview-recording.md 완료 조건 3·4·5·6).
 *
 * <p>서명된 웹훅을 실제 엔드포인트로 보내 어댑터 분기까지 관통한다. 발행 메시지는 워커 계약
 * ({@code AudioAnalysisRequested.decode} — 문자열 필드 sessionId/bucket/objectKey)과 자구
 * 대조한다(언어 경계라 코드 공유 불가 — 필드 자구가 곧 계약).
 */
class SessionRecordingIntegrationTest extends InterviewSessionIntegrationTestSupport {

    private static final String WEBHOOK_URI = "/api/v1/webhook/livekit";
    private static final String BUCKET = "kkori-rec";
    private static final String OBJECT_KEY = "recordings/room-rec-1-123.ogg";

    @Autowired
    private LiveKitProperties liveKitProperties;

    @BeforeEach
    void cleanAudioStream() {
        redisTemplate.delete(AudioAnalysisRequestedMessage.STREAM_KEY);
    }

    @Test
    @DisplayName("EGRESS_COMPLETE 수신 시 세션이 terminal이어도 bucket·objectKey가 기록되고 계약 필드로 발행된다 (완료 조건 3·6)")
    void completeRecordsAndPublishesContractFields() throws Exception {
        long sessionId = endedSessionWithEgress("kakao-rec-1", "room-rec-1", "EG_it_1");

        postWebhook(egressBody("EG_it_1", "EGRESS_COMPLETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(recordingColumn(sessionId, "recording_bucket")).isEqualTo(BUCKET);
        assertThat(recordingColumn(sessionId, "recording_object_key")).isEqualTo(OBJECT_KEY);

        // 워커 계약 자구 대조 — 필드는 정확히 셋, 전부 문자열 (AudioAnalysisRequested.decode 파싱 가능 형태)
        List<MapRecord<String, Object, Object>> records = streamRecords();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .hasSize(3)
                .containsEntry("sessionId", String.valueOf(sessionId))
                .containsEntry("bucket", BUCKET)
                .containsEntry("objectKey", OBJECT_KEY);
    }

    @Test
    @DisplayName("동일 egress_ended를 2회 수신해도 발행은 1회다 (완료 조건 4 — 멱등 가드)")
    void duplicateWebhookPublishesOnce() throws Exception {
        endedSessionWithEgress("kakao-rec-2", "room-rec-2", "EG_it_2");
        String body = egressBody("EG_it_2", "EGRESS_COMPLETE");

        postWebhook(body).andExpect(status().isOk());
        postWebhook(body).andExpect(status().isOk());

        assertThat(streamRecords()).hasSize(1);
    }

    @Test
    @DisplayName("EGRESS_FAILED는 기록·발행 없이 200으로 끝난다 (완료 조건 5)")
    void failedEgressLeavesNoTrace() throws Exception {
        long sessionId = endedSessionWithEgress("kakao-rec-3", "room-rec-3", "EG_it_3");

        postWebhook(egressBody("EG_it_3", "EGRESS_FAILED")).andExpect(status().isOk());

        assertThat(recordingColumn(sessionId, "recording_bucket")).isNull();
        assertThat(recordingColumn(sessionId, "recording_object_key")).isNull();
        assertThat(streamRecords()).isEmpty();
    }

    @Test
    @DisplayName("미등록 egressId는 no-op으로 200이다 (역매핑 실패 세션의 웹훅 흡수)")
    void unknownEgressIdIsNoop() throws Exception {
        endedSessionWithEgress("kakao-rec-4", "room-rec-4", "EG_it_4");

        postWebhook(egressBody("EG_unknown", "EGRESS_COMPLETE")).andExpect(status().isOk());

        assertThat(streamRecords()).isEmpty();
    }

    // ─── 픽스처·헬퍼 ───

    /** egress_ended는 room_finished 뒤에 도착한다(업로드 시간) — terminal(ENDED) 세션으로 시딩한다. */
    private long endedSessionWithEgress(String providerId, String roomName, String egressId) {
        long userId = saveUser(providerId);
        long sessionId = sessionInStatus(userId, null, SessionStatus.ENDED, roomName);
        jdbcTemplate.update("UPDATE interview_session SET egress_id = ? WHERE id = ?", egressId, sessionId);
        return sessionId;
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        return mockMvc.perform(post(WEBHOOK_URI)
                .contentType("application/webhook+json")
                .header("Authorization", LiveKitWebhookTestSigner.sign(
                        body, liveKitProperties.apiKey(), liveKitProperties.apiSecret()))
                .content(body));
    }

    /** LiveKit 발신 형식의 egress_ended — bucket은 fileResults가 아닌 원요청 echo에 실린다 (PRD 기능 2). */
    private static String egressBody(String egressId, String status) {
        return """
                {"event":"egress_ended","egressInfo":{"egressId":"%s","roomName":"room-any","status":"%s",\
                "roomComposite":{"roomName":"room-any","audioOnly":true,\
                "fileOutputs":[{"filepath":"recordings/{room_name}-{time}.ogg","s3":{"bucket":"%s","region":"ap-northeast-2"}}]},\
                "fileResults":[{"filename":"%s"}]}}"""
                .formatted(egressId, status, BUCKET, OBJECT_KEY);
    }

    private String recordingColumn(long sessionId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM interview_session WHERE id = ?", String.class, sessionId);
    }

    private List<MapRecord<String, Object, Object>> streamRecords() {
        return redisTemplate.opsForStream()
                .range(AudioAnalysisRequestedMessage.STREAM_KEY, Range.unbounded());
    }
}
