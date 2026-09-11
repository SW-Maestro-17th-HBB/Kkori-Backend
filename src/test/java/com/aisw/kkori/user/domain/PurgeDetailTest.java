package com.aisw.kkori.user.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PurgeDetail} jsonb 계약 검증 (PRD deletion.md 기능 3).
 *
 * <p>Hibernate의 JSON 매퍼는 Boot의 ObjectMapper가 아니라 모듈 자동 등록만 한 기본 매퍼라,
 * 같은 조건({@code findAndAddModules})으로 직렬화 형태를 검증한다 — 시각은 숫자 타임스탬프가
 * 아닌 ISO-8601 문자열이어야 운영 조회가 가능하다.
 */
class PurgeDetailTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("건수 없는 필드는 생략되고 시각은 ISO-8601 문자열로 직렬화된다")
    void serializesCompactly() throws Exception {
        PurgeDetail detail = PurgeDetail.empty()
                .attempt(Instant.parse("2026-01-18T09:10:00Z"))
                .withStep(PurgeDetail.STEP_RESUMES, new PurgeDetail.StepResult(
                        PurgeDetail.StepResult.DONE, 3, 41, 3, null, null, null))
                .withStep(PurgeDetail.STEP_UNLINK, PurgeDetail.StepResult.of(
                        PurgeDetail.StepResult.SKIPPED_ACTIVE_ACCOUNT))
                .withError("S3Exception: Access Denied");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(detail));

        assertThat(json.get("attempts").asInt()).isEqualTo(1);
        assertThat(json.get("lastAttemptAt").asText()).isEqualTo("2026-01-18T09:10:00Z");
        assertThat(json.get("steps").get("resumes").get("rows").asInt()).isEqualTo(3);
        assertThat(json.get("steps").get("resumes").has("transcripts")).isFalse();
        assertThat(json.get("steps").get("unlink").get("status").asText()).isEqualTo("SKIPPED_ACTIVE_ACCOUNT");
        assertThat(json.get("steps").get("unlink").has("rows")).isFalse();
        assertThat(json.get("lastError").asText()).isEqualTo("S3Exception: Access Denied");
    }

    @Test
    @DisplayName("직렬화·역직렬화 왕복이 동일하고, 모르는 필드는 무시된다")
    void roundTripAndIgnoresUnknown() throws Exception {
        PurgeDetail original = PurgeDetail.empty()
                .attempt(Instant.parse("2026-01-18T09:10:00Z"))
                .withStep(PurgeDetail.STEP_SESSIONS, new PurgeDetail.StepResult(
                        PurgeDetail.StepResult.DONE, 5, null, null, 4, 4, 0));

        PurgeDetail restored = mapper.readValue(mapper.writeValueAsString(original), PurgeDetail.class);
        assertThat(restored).isEqualTo(original);

        PurgeDetail withUnknown = mapper.readValue("""
                {"attempts":2,"future":true,"steps":{"reports":{"status":"DONE","rows":1,"extra":9}}}
                """, PurgeDetail.class);
        assertThat(withUnknown.attempts()).isEqualTo(2);
        assertThat(withUnknown.steps().get(PurgeDetail.STEP_REPORTS).rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("attempt는 횟수·시각만 올리고 단계 기록·마지막 오류는 유지한다 (재시도 진단 재료)")
    void attemptKeepsPreviousRecords() {
        PurgeDetail failedOnce = PurgeDetail.empty()
                .attempt(Instant.parse("2026-01-18T09:00:00Z"))
                .withStep(PurgeDetail.STEP_RESUMES, PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE))
                .withError("boom");

        PurgeDetail retried = failedOnce.attempt(Instant.parse("2026-01-18T09:10:00Z"));

        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(retried.lastAttemptAt()).isEqualTo(Instant.parse("2026-01-18T09:10:00Z"));
        assertThat(retried.steps()).containsKey(PurgeDetail.STEP_RESUMES);
        assertThat(retried.lastError()).isEqualTo("boom");
    }

    @Test
    @DisplayName("steps는 불변이다")
    void stepsAreImmutable() {
        PurgeDetail detail = PurgeDetail.empty()
                .withStep(PurgeDetail.STEP_RESUMES, PurgeDetail.StepResult.of(PurgeDetail.StepResult.DONE));

        assertThatThrownBy(() -> detail.steps().put("x", PurgeDetail.StepResult.failed()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PurgeDetail(0, null, null, null).steps()).isEqualTo(Map.of());
    }
}
