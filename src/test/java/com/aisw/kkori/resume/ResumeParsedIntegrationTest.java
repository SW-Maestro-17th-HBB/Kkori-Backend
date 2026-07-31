package com.aisw.kkori.resume;

import com.aisw.kkori.ResumeSeeder;
import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파싱 결과 조회·수정·재분석 통합 테스트 (docs/requirements/resume/resume.md §4 검증 기준 1:1).
 *
 * <p>EMBEDDED·FAILED 상태는 Spring 코드로 만들 수 없으므로(상태 쓰기 주체는 Worker)
 * JdbcTemplate로 Worker를 연기한다 — Worker도 SQL로 갱신하므로 계약에 충실한 시뮬레이션이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeParsedIntegrationTest {

    /** 실제 저장된 유저 id — 수정·재분석이 user 행 잠금을 잡으므로(세션 생성과 직렬화) 가짜 상수로는 401이 난다. */
    private long userId;
    private long otherUserId;

    private static final String VALID_STRUCTURED_DATA = """
            {
              "profile": {"name": "김꼬리", "email": "kkori@example.com"},
              "skills": [{"category": "백엔드", "items": ["Java", "Spring Boot"]}],
              "projects": [{"name": "꼬리", "role": "백엔드", "description": "AI 면접 서비스", "techStacks": ["Spring"]}],
              "experiences": [{"title": "인턴", "description": "6개월"}]
            }
            """;

    @Autowired MockMvc mockMvc;
    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired InterviewSessionRepository interviewSessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        interviewSessionRepository.deleteAll();
        statusRepository.deleteAll();
        resumeRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.delete(ResumeParseRequestedMessage.STREAM_KEY);
        userId = userRepository.save(User.create("kakao-parsed-1", "p1@example.com", "본인")).getId();
        otherUserId = userRepository.save(User.create("kakao-parsed-2", "p2@example.com", "타인")).getId();
    }

    // ─── 조회 ───

    @Test
    @DisplayName("EMBEDDED 이력서 조회 시 200과 함께 structuredData가 반환되고 rawText는 없다")
    void getParsed_embedded_returnsStructuredData() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(get("/api/v1/resumes/{resumeId}/parsed", resumeId).with(authOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.analysisStatus").value("EMBEDDED"))
                .andExpect(jsonPath("$.data.structuredData.profile.name").value("김꼬리"))
                .andExpect(jsonPath("$.data.structuredData.skills[0].items[0]").value("Java"))
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.rawText").doesNotExist());   // 원문은 계약상 미제공 (PRD §4)
    }

    @Test
    @DisplayName("분석 진행 중이면 조회가 409 R010으로 거부된다")
    void getParsed_inProgress_returns409() throws Exception {
        long resumeId = inProgressResume(userId);

        mockMvc.perform(get("/api/v1/resumes/{resumeId}/parsed", resumeId).with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R010"));
    }

    @Test
    @DisplayName("FAILED 이력서는 조회가 409 R011로 거부된다 (복구는 재분석의 몫)")
    void getParsed_failed_returns409() throws Exception {
        long resumeId = failedResume(userId);

        mockMvc.perform(get("/api/v1/resumes/{resumeId}/parsed", resumeId).with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R011"));
    }

    @Test
    @DisplayName("타인의 이력서 조회는 403 R009 — 404가 아니라 명확한 거부 (PRD §4)")
    void getParsed_othersResume_returns403() throws Exception {
        long resumeId = embeddedResume(otherUserId);

        mockMvc.perform(get("/api/v1/resumes/{resumeId}/parsed", resumeId).with(authOf(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("R009"));
    }

    @Test
    @DisplayName("없는 이력서 조회는 404 R008")
    void getParsed_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/resumes/{resumeId}/parsed", 999_999L).with(authOf(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("R008"));
    }

    // ─── 수정 ───

    @Test
    @DisplayName("수정 성공 시 DB에 반영되고 분석 요청은 발행되지 않는다 (수정 ≠ 재분석)")
    void updateParsed_success_savesWithoutPublishing() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("""
                                {"profile": {"name": "수정된 이름", "email": "new@example.com"},
                                 "skills": [{"category": "백엔드", "items": ["Kotlin"]}],
                                 "projects": [], "experiences": []}
                                """))
                        .with(authOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.structuredData.profile.name").value("수정된 이름"))
                .andExpect(jsonPath("$.data.analysisStatus").value("EMBEDDED"));   // 수정은 상태를 바꾸지 않음

        // DB 왕복 검증 — jsonb에 저장된 것이 타입드 record로 되돌아온다
        Resume updated = resumeRepository.findById(resumeId).orElseThrow();
        assertThat(updated.getStructuredData().profile().name()).isEqualTo("수정된 이름");
        assertThat(updated.getStructuredData().skills().get(0).items()).containsExactly("Kotlin");

        // 스트림 무발행 — 색인 반영은 사용자가 재분석을 눌러야 일어난다
        assertThat(streamRecords()).isEmpty();
    }

    @Test
    @DisplayName("수정 응답의 updatedAt은 이번 저장 시각이다 (flush 시점 버그 회귀 테스트)")
    void updateParsed_responseCarriesFreshUpdatedAt() throws Exception {
        long resumeId = embeddedResume(userId);

        // 두 수정의 내용이 달라야 한다 — 같으면 더티 체킹이 UPDATE를 생략해 updatedAt이 안 바뀐다
        Instant first = updatedAtOf(patchName(resumeId, "1차 수정"));
        Instant second = updatedAtOf(patchName(resumeId, "2차 수정"));

        // flush 전에 DTO를 만들면 두 번째 응답이 첫 번째와 같은(낡은) 시각을 담는다
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("필드 누락·빈 배열은 유효하다 — 내용의 올바름은 시스템이 판정하지 않는다")
    void updateParsed_emptyAndMissingFields_allowed() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("{\"skills\": []}"))   // profile·projects·experiences 전부 누락
                        .with(authOf(userId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("JSON 구조 오류(배열 자리에 문자열)는 400 C002 — 역직렬화 단계에서 차단된다")
    void updateParsed_structuralError_returns400() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("{\"skills\": \"배열이어야 함\"}"))
                        .with(authOf(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("배열 내 null 요소는 400 C002 + fieldErrors — 검증 단계에서 차단된다")
    void updateParsed_nullInArray_returns400WithFieldErrors() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("{\"skills\": [null]}"))
                        .with(authOf(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors").isNotEmpty());   // 어느 필드가 틀렸는지 알려주는 유일한 경로
    }

    @Test
    @DisplayName("structuredData 자체가 없으면 400 C002 + fieldErrors")
    void updateParsed_missingStructuredData_returns400() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authOf(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.fieldErrors").isNotEmpty());
    }

    @Test
    @DisplayName("직렬화 실측 100KB 초과는 400 C002 — Content-Length가 아니라 저장될 크기 기준")
    void updateParsed_over100KB_returns400() throws Exception {
        long resumeId = embeddedResume(userId);
        String bigDescription = "가".repeat(60_000);   // UTF-8 3바이트 × 60,000 = 180KB > 100KB

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("""
                                {"projects": [{"name": "p", "role": "r", "description": "%s", "techStacks": []}]}
                                """.formatted(bigDescription)))
                        .with(authOf(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("FAILED 이력서는 수정이 409 R011로 거부된다")
    void updateParsed_failed_returns409() throws Exception {
        long resumeId = failedResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(VALID_STRUCTURED_DATA))
                        .with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R011"));
    }

    @Test
    @DisplayName("분석 진행 중이면 수정이 409 R010으로 거부된다 (Worker와의 동시 쓰기 방지)")
    void updateParsed_inProgress_returns409() throws Exception {
        long resumeId = inProgressResume(userId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(VALID_STRUCTURED_DATA))
                        .with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R010"));
    }

    // ─── 재분석 ───

    @Test
    @DisplayName("EMBEDDED에서 재분석하면 REINDEX가 발행되고 상태가 EMBEDDING으로 재시작된다")
    void reanalyze_embedded_publishesReindex() throws Exception {
        long resumeId = embeddedResume(userId);

        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resumeId).with(authOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.analysisStatus").value("EMBEDDING"));

        assertThat(statusRepository.findByResumeId(resumeId))
                .hasValueSatisfying(s -> assertThat(s.getParseStatus()).isEqualTo(AnalysisStatus.EMBEDDING));

        Resume resume = resumeRepository.findById(resumeId).orElseThrow();
        List<MapRecord<String, Object, Object>> records = streamRecords();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .containsEntry("resumeId", String.valueOf(resumeId))
                .containsEntry("userId", String.valueOf(userId))
                .containsEntry("bucket", resume.getOriginalFileBucket())     // 설정값이 아니라 저장 당시 DB 기록
                .containsEntry("objectKey", resume.getOriginalFileKey())
                .containsEntry("mode", "REINDEX");
    }

    @Test
    @DisplayName("FAILED에서 재분석하면 FULL이 발행되고 상태 UPLOADED + 이전 실패 정보가 초기화된다")
    void reanalyze_failed_publishesFullAndClearsFailure() throws Exception {
        long resumeId = failedResume(userId);

        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resumeId).with(authOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));

        assertThat(statusRepository.findByResumeId(resumeId)).hasValueSatisfying(s -> {
            assertThat(s.getParseStatus()).isEqualTo(AnalysisStatus.UPLOADED);
            assertThat(s.getErrorMessage()).isNull();     // 이전 런의 흔적은 지운다
            assertThat(s.getFailedAt()).isNull();
            assertThat(s.getRetryCount()).isEqualTo(3);   // 단 retryCount는 Worker 소유라 불변
        });

        List<MapRecord<String, Object, Object>> records = streamRecords();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue()).containsEntry("mode", "FULL");
    }

    @Test
    @DisplayName("분석 진행 중이면 재분석이 409 R010으로 거부되고 아무것도 발행되지 않는다")
    void reanalyze_inProgress_returns409WithoutPublishing() throws Exception {
        long resumeId = inProgressResume(userId);

        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resumeId).with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R010"));

        assertThat(streamRecords()).isEmpty();
    }

    // ─── 사용 중 차단 (R013 — interview-session-creation.md 기능 1) ───

    @Test
    @DisplayName("non-terminal 세션(PENDING)이 참조하는 이력서는 수정이 409 R013로 거부된다")
    void updateParsed_resumeInUse_returns409() throws Exception {
        long resumeId = embeddedResume(userId);
        referencingSession(resumeId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(VALID_STRUCTURED_DATA))
                        .with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R013"));
    }

    @Test
    @DisplayName("ACTIVE 세션이 참조하는 이력서도 동일하게 R013로 차단된다")
    void updateParsed_activeSessionInUse_returns409() throws Exception {
        long resumeId = embeddedResume(userId);
        long sessionId = referencingSession(resumeId);
        jdbcTemplate.update("UPDATE interview_session SET status = 'ACTIVE' WHERE id = ?", sessionId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(VALID_STRUCTURED_DATA))
                        .with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R013"));
    }

    @Test
    @DisplayName("사용 중 이력서는 재분석도 409 R013로 거부되고 아무것도 발행되지 않는다")
    void reanalyze_resumeInUse_returns409WithoutPublishing() throws Exception {
        long resumeId = embeddedResume(userId);
        referencingSession(resumeId);

        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resumeId).with(authOf(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("R013"));

        assertThat(streamRecords()).isEmpty();
    }

    @Test
    @DisplayName("참조 세션이 terminal이 되면 수정·재분석이 다시 허용된다")
    void terminalSessionReleasesResume() throws Exception {
        long resumeId = embeddedResume(userId);
        long sessionId = referencingSession(resumeId);
        jdbcTemplate.update("UPDATE interview_session SET status = 'ABORTED' WHERE id = ?", sessionId);

        mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(VALID_STRUCTURED_DATA))
                        .with(authOf(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resumeId).with(authOf(userId)))
                .andExpect(status().isOk());
    }

    /** 해당 이력서를 참조하는 PENDING 세션 — 상태 전이는 SQL로 연기한다(전이 코드는 후속 스토리). */
    private long referencingSession(long resumeId) {
        return interviewSessionRepository.save(InterviewSession.pending(
                userId, resumeId, InterviewType.THIRTY_MIN, Position.BACKEND,
                "room-inuse-" + System.nanoTime())).getId();
    }

    // ─── Worker 연기 헬퍼 — 공용 픽스처({@link ResumeSeeder})에 위임 ───

    private ResumeSeeder resumeSeeder() {
        return new ResumeSeeder(resumeRepository, statusRepository, jdbcTemplate);
    }

    /** 분석 완료(EMBEDDED) + structured_data까지 채운 이력서. */
    private long embeddedResume(long userId) {
        return resumeSeeder().embedded(userId, VALID_STRUCTURED_DATA);
    }

    private long failedResume(long userId) {
        return resumeSeeder().failed(userId);
    }

    private long inProgressResume(long userId) {
        return resumeSeeder().inProgress(userId);
    }

    // ─── 요청·응답 헬퍼 ───

    /** JwtAuthenticationFilter가 principal에 심는 것과 동일한 형태(Long)로 인증을 주입한다. */
    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static String updateBody(String structuredDataJson) {
        return "{\"structuredData\": " + structuredDataJson + "}";
    }

    private String patchName(long resumeId, String name) throws Exception {
        return mockMvc.perform(patch("/api/v1/resumes/{resumeId}/parsed", resumeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("{\"profile\": {\"name\": \"" + name + "\", \"email\": \"k@x.com\"}}"))
                        .with(authOf(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Instant updatedAtOf(String responseBody) {
        return Instant.parse(JsonPath.read(responseBody, "$.data.updatedAt"));
    }

    private List<MapRecord<String, Object, Object>> streamRecords() {
        return redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
    }
}
