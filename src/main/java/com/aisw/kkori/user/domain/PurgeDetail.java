package com.aisw.kkori.user.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 파기 진행 기록 — {@code deletion_log.purge_detail}(jsonb) 스키마의 유일한 정의
 * (PRD deletion.md 기능 3 "purge_detail 계약").
 *
 * <p>건수와 단계 상태만 담는다 — 개인정보(회원번호·이메일·이름·파일명·객체 키)는 넣지 않는다.
 * 불변 record이며 갱신은 {@code with*}로 새 인스턴스를 만든다. 쓰기는 파기 배치가 선점 이후
 * 펜싱 조건부 UPDATE로만 수행한다(기능 2).
 *
 * <p>읽기는 unknown 필드를 무시한다(필드 추가 후 구버전 인스턴스가 낡은 기록을 읽어도 실패하지 않게).
 * {@code lastAttemptAt}은 ISO-8601 문자열로 직렬화한다(운영 조회 가독성).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PurgeDetail(
        int attempts,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastAttemptAt,
        Map<String, StepResult> steps,
        String lastError
) {

    public static final String STEP_RESUMES = "resumes";
    public static final String STEP_SESSIONS = "sessions";
    public static final String STEP_REPORTS = "reports";
    public static final String STEP_REFRESH_TOKENS = "refreshTokens";
    public static final String STEP_UNLINK = "unlink";
    public static final String STEP_IDENTIFIERS = "identifiers";

    public PurgeDetail {
        steps = steps == null ? Map.of() : Map.copyOf(orderedCopy(steps));
    }

    /** 아직 시도되지 않은 건의 초기 기록. */
    public static PurgeDetail empty() {
        return new PurgeDetail(0, null, Map.of(), null);
    }

    /** 선점 시 시도 횟수·시각 갱신 — 이전 단계 기록·마지막 오류는 유지한다(재시도 진단 재료). */
    public PurgeDetail attempt(Instant at) {
        return new PurgeDetail(attempts + 1, at, steps, lastError);
    }

    public PurgeDetail withStep(String step, StepResult result) {
        LinkedHashMap<String, StepResult> updated = orderedCopy(steps);
        updated.put(step, result);
        return new PurgeDetail(attempts, lastAttemptAt, updated, lastError);
    }

    public PurgeDetail withError(String lastError) {
        return new PurgeDetail(attempts, lastAttemptAt, steps, lastError);
    }

    private static LinkedHashMap<String, StepResult> orderedCopy(Map<String, StepResult> source) {
        return new LinkedHashMap<>(source);
    }

    /**
     * 단계별 결과. {@code status}는 {@code DONE}·{@code SKIPPED}(unlink는 생략 사유를 세분한
     * {@code SKIPPED_ACTIVE_ACCOUNT}·{@code SKIPPED_ALREADY_UNLINKED})·{@code FAILED}.
     * 건수 필드는 단계에 해당하는 것만 채우고 나머지는 생략된다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepResult(
            String status,
            Integer rows,
            Integer chunks,
            Integer s3Objects,
            Integer transcripts,
            Integer recordings,
            Integer abortedLeftovers
    ) {

        public static final String DONE = "DONE";
        public static final String SKIPPED = "SKIPPED";
        public static final String SKIPPED_ACTIVE_ACCOUNT = "SKIPPED_ACTIVE_ACCOUNT";
        public static final String SKIPPED_ALREADY_UNLINKED = "SKIPPED_ALREADY_UNLINKED";
        public static final String FAILED = "FAILED";

        public static StepResult of(String status) {
            return new StepResult(status, null, null, null, null, null, null);
        }

        public static StepResult failed() {
            return of(FAILED);
        }
    }
}
