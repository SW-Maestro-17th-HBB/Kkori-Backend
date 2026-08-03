package com.aisw.kkori.report;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.report.domain.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 질문-답변 타임라인 조회 통합 테스트 (docs/requirements/report/report.md §4 검증 기준 1:1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReportFixtures.class})
class ReportTimelineIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ReportFixtures fixtures;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        fixtures.deleteAll();
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static String utterance(int number, int parent, String speaker, String type,
                                    String content, String spokenAt) {
        return """
                {"questionNumber": %d, "parentQuestionNumber": %d, "speaker": "%s",
                 "questionType": "%s", "content": "%s", "spokenAt": "%s"}"""
                .formatted(number, parent, speaker, type, content, spokenAt);
    }

    /** 픽스처 피드백(질문 1·2)과 결합되는 기본 대본 — 질문 1은 답변이 두 발화로 쪼개져 있다. */
    private void seedDefaultTranscript(long reportId) {
        fixtures.transcript(fixtures.sessionIdOf(reportId), "[" + String.join(",",
                utterance(1, 1, "INTERVIEWER", "MAIN", "자기소개를 부탁드립니다.", "2026-07-01T10:00:00Z"),
                utterance(1, 1, "USER", "MAIN", "안녕하세요,", "2026-07-01T10:00:10Z"),
                utterance(1, 1, "USER", "MAIN", "3년차 백엔드 개발자입니다.", "2026-07-01T10:00:20Z"),
                utterance(2, 1, "INTERVIEWER", "TAIL", "가장 어려웠던 점은?", "2026-07-01T10:01:00Z"),
                utterance(2, 1, "USER", "TAIL", "레거시 파악이 어려웠습니다.", "2026-07-01T10:01:10Z")
        ) + "]");
    }

    // ─── 검증 기준 (PRD §4) ───

    @Test
    @DisplayName("타임라인이 질문 단위로 그룹핑되고 질문·답변 텍스트와 평가(점수·피드백·약점 태그)가 결합된다")
    void timelineGroupsByQuestionAndJoinsEvaluation() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        seedDefaultTranscript(reportId);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].questionNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].question").value("자기소개를 부탁드립니다."))
                // 쪼개진 사용자 발화는 시간순으로 이어붙인다
                .andExpect(jsonPath("$.data.items[0].answer").value("안녕하세요, 3년차 백엔드 개발자입니다."))
                .andExpect(jsonPath("$.data.items[0].evaluation.logicScore").value(80))
                .andExpect(jsonPath("$.data.items[0].evaluation.specificityScore").value(75))
                .andExpect(jsonPath("$.data.items[0].evaluation.technicalAccuracyScore").value(82))
                .andExpect(jsonPath("$.data.items[0].evaluation.feedback").value("두괄식으로 시작하면 더 좋아요"))
                .andExpect(jsonPath("$.data.items[0].evaluation.weaknessTags[0]").value("두괄식 부족"))
                .andExpect(jsonPath("$.data.items[1].questionNumber").value(2))
                .andExpect(jsonPath("$.data.items[1].evaluation.feedback").value("사례가 부족합니다"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("questionType과 parentQuestionNumber는 대본 값 그대로 반환된다 (꼬리 구별은 questionType으로)")
    void questionTypeAndParentPassedThrough() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        seedDefaultTranscript(reportId);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].questionType").value("MAIN"))
                .andExpect(jsonPath("$.data.items[0].parentQuestionNumber").value(1))
                .andExpect(jsonPath("$.data.items[1].questionType").value("TAIL"))
                .andExpect(jsonPath("$.data.items[1].parentQuestionNumber").value(1));
    }

    @Test
    @DisplayName("항목·발화 순서는 spokenAt 오름차순이다 — 문자열이 아닌 실제 시각 기준(오프셋·소수점 자릿수 혼합)")
    void orderedByActualSpokenTime() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        // 대본 배열 순서는 뒤섞고, 문자열 정렬이라면 순서가 뒤집히는 값들로 구성한다
        // ("...00.500Z" < "...00Z", "10:30:00Z" < "19:00:40+09:00")
        fixtures.transcript(fixtures.sessionIdOf(reportId), "[" + String.join(",",
                utterance(2, 1, "INTERVIEWER", "TAIL", "꼬리 질문입니다.", "2026-07-01T11:00:00Z"),
                utterance(1, 1, "USER", "MAIN", "넷", "2026-07-01T10:30:00Z"),
                utterance(1, 1, "USER", "MAIN", "셋", "2026-07-01T19:00:40+09:00"),
                utterance(1, 1, "USER", "MAIN", "첫", "2026-07-01T10:00:00Z"),
                utterance(1, 1, "USER", "MAIN", "둘", "2026-07-01T10:00:00.500Z"),
                utterance(1, 1, "INTERVIEWER", "MAIN", "본질문입니다.", "2026-07-01T09:59:00Z"),
                utterance(2, 1, "USER", "TAIL", "꼬리 답변입니다.", "2026-07-01T11:00:10Z")
        ) + "]");

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].questionNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].answer").value("첫 둘 셋 넷"))
                .andExpect(jsonPath("$.data.items[1].questionNumber").value(2));
    }

    @Test
    @DisplayName("응답에 resume_context가 포함되지 않는다 (저장돼 있어도 노출 형태 미정 — PRD §4)")
    void resumeContextNotExposed() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        seedDefaultTranscript(reportId);
        jdbcTemplate.update(
                "UPDATE report_feedbacks SET resume_context = '{\"quote\": \"이력서 발췌\"}'::jsonb "
                        + "WHERE report_id = ?", reportId);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                // doesNotExist()는 null 로 노출돼도 통과한다 — 경로 자체의 부재를 검증
                .andExpect(jsonPath("$.data.items[0].evaluation.resumeContext").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.items[0].evaluation.resume_context").doesNotHaveJsonPath());
    }

    @Test
    @DisplayName("평가가 없는 질문은 evaluation이 null로 반환된다 (방어적 결합)")
    void evaluationNullWhenFeedbackMissing() throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        fixtures.transcript(fixtures.sessionIdOf(reportId), "[" + String.join(",",
                utterance(1, 1, "INTERVIEWER", "MAIN", "질문입니다.", "2026-07-01T10:00:00Z"),
                utterance(1, 1, "USER", "MAIN", "답변입니다.", "2026-07-01T10:00:10Z"),
                utterance(3, 3, "INTERVIEWER", "MAIN", "평가 없는 질문입니다.", "2026-07-01T10:02:00Z")
        ) + "]");

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[1].questionNumber").value(3))
                .andExpect(jsonPath("$.data.items[1].evaluation").value(nullValue()))
                // 답변 없는 질문의 answer는 빈 문자열 (질문만 하고 종료된 경우)
                .andExpect(jsonPath("$.data.items[1].answer").value(""));
    }

    /** 대본 계약 위반 케이스 — 전부 조립 중 원시 예외(NPE 등) 대신 읽기 계층에서 500으로 변환돼야 한다. */
    static Stream<Arguments> 계약_위반_대본() {
        String valid = "\"questionNumber\": 1, \"parentQuestionNumber\": 1, \"speaker\": \"INTERVIEWER\", "
                + "\"questionType\": \"MAIN\"";
        return Stream.of(
                Arguments.of("문서 전체가 JSON null", "null"),
                Arguments.of("배열 원소가 null", "[null]"),
                Arguments.of("questionNumber 누락", """
                        [{"parentQuestionNumber": 1, "speaker": "INTERVIEWER", "questionType": "MAIN",
                          "content": "질문입니다.", "spokenAt": "2026-07-01T10:00:00Z"}]"""),
                Arguments.of("content 누락", "[{" + valid + ", \"spokenAt\": \"2026-07-01T10:00:00Z\"}]"),
                Arguments.of("spokenAt null", "[{" + valid + ", \"content\": \"질문입니다.\", \"spokenAt\": null}]"),
                Arguments.of("spokenAt 형식 오류",
                        "[{" + valid + ", \"content\": \"질문입니다.\", \"spokenAt\": \"어제 오전\"}]")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("계약_위반_대본")
    @DisplayName("대본이 계약을 위반하면 원시 예외 대신 500(C001)으로 응답한다")
    void internalErrorOnTranscriptContractViolation(String caseName, String contentJson) throws Exception {
        long reportId = fixtures.evaluatedReport(USER_ID, null);
        fixtures.transcript(fixtures.sessionIdOf(reportId), contentJson);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    // ─── 접근 규칙 (§3과 동일 — 404 → 403 → 409) ───

    @Test
    @DisplayName("타인의 리포트 타임라인 조회는 403 RP002")
    void forbiddenForOtherUser() throws Exception {
        long reportId = fixtures.evaluatedReport(OTHER_USER_ID, null);
        seedDefaultTranscript(reportId);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RP002"));
    }

    @Test
    @DisplayName("존재하지 않는 리포트의 타임라인 조회는 404 RP001")
    void notFoundForUnknownReport() throws Exception {
        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", 999_999L).with(authOf(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RP001"));
    }

    @ParameterizedTest(name = "{0} 리포트의 타임라인 조회는 409 {1}")
    @CsvSource({
            "PENDING, RP003",     // 생성 진행 중
            "PROCESSING, RP003",  // 생성 진행 중
            "FAILED, RP004",      // 생성 실패 — 복구는 재생성의 몫
    })
    @DisplayName("완성되지 않은 리포트의 타임라인 조회는 409로 거부된다")
    void conflictWhenNotCompleted(ReportStatus status, String errorCode) throws Exception {
        long reportId = fixtures.reportWithStatus(USER_ID, status);

        mockMvc.perform(get("/api/v1/reports/{reportId}/timeline", reportId).with(authOf(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(errorCode));
    }
}
