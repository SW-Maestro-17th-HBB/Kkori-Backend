package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.StructuredData;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * metadata 조립 규칙 검증 (agent-dispatch.md 기능 1).
 *
 * <p>계약 픽스처 테스트는 산출 문자열이 PRD의 canonical sample과 <b>자구까지 일치</b>함을
 * 단언한다 — Kkori-AI 테스트가 같은 픽스처를 파싱하므로, 여기 기대값을 바꾸는 것은 곧
 * 크로스 레포 계약 변경이다.
 */
class DispatchMetadataAssemblerTest {

    private final DispatchMetadataAssembler assembler = new DispatchMetadataAssembler(new ObjectMapper());

    /** PRD 계약 픽스처의 입력 structured_data — 값·자구를 문서와 동일하게 유지할 것. */
    private static StructuredData contractFixture() {
        return new StructuredData(
                new StructuredData.Profile("홍길동", "hong@example.com"),
                List.of(
                        new StructuredData.Skill("언어", List.of("Java", "Python")),
                        new StructuredData.Skill("프레임워크", List.of("Spring Boot"))),
                List.of(new StructuredData.Project(
                        "Kkori", "백엔드",
                        "AI 면접 준비 서비스의 세션 생성 API와 LiveKit 실시간 음성 연동을 설계·구현. "
                                + "user 행 잠금 기반 동시성 제어로 유저당 단일 세션 불변식을 보장",
                        List.of("Spring Boot", "PostgreSQL"))),
                List.of(new StructuredData.Experience(
                        "ABC 커머스 인턴", "결제 정산 배치의 지연 문제를 인덱스 재설계로 개선하고 처리 시간을 40% 단축")));
    }

    private static final String CONTRACT_METADATA =
            "{\"sessionId\":\"123\",\"interviewType\":\"THIRTY_MIN\",\"position\":\"BACKEND\","
                    + "\"resumeContext\":\"[기술 스택]\\n- 언어: Java, Python\\n- 프레임워크: Spring Boot"
                    + "\\n\\n[프로젝트]\\n- Kkori (백엔드): AI 면접 준비 서비스의 세션 생성 API와 LiveKit "
                    + "실시간 음성 연동을 설계·구현. user 행 잠금 기반 동시성 제어로 유저당 단일 세션 불변식을 보장"
                    + " (기술: Spring Boot, PostgreSQL)"
                    + "\\n\\n[경험]\\n- ABC 커머스 인턴: 결제 정산 배치의 지연 문제를 인덱스 재설계로 개선하고 "
                    + "처리 시간을 40% 단축\"}";

    private static final String CONTRACT_METADATA_NO_RESUME =
            "{\"sessionId\":\"124\",\"interviewType\":\"FIVE_MIN\",\"position\":\"FRONTEND\"}";

    @Test
    @DisplayName("계약 픽스처: THIRTY_MIN + 이력서 → PRD canonical sample과 자구까지 일치한다")
    void contractFixtureMatchesVerbatim() {
        String metadata = assembler.assemble(123, InterviewType.THIRTY_MIN, Position.BACKEND, contractFixture());

        assertThat(metadata).isEqualTo(CONTRACT_METADATA);
    }

    @Test
    @DisplayName("계약 픽스처: 이력서 없는 FIVE_MIN → resumeContext 필드 자체가 생략된다")
    void contractFixtureWithoutResumeMatchesVerbatim() {
        String metadata = assembler.assemble(124, InterviewType.FIVE_MIN, Position.FRONTEND, null);

        assertThat(metadata).isEqualTo(CONTRACT_METADATA_NO_RESUME);
    }

    @Test
    @DisplayName("profile(이름·이메일)은 어떤 경우에도 metadata에 포함되지 않는다")
    void profileIsNeverIncluded() {
        String metadata = assembler.assemble(123, InterviewType.THIRTY_MIN, Position.BACKEND, contractFixture());

        assertThat(metadata).doesNotContain("홍길동").doesNotContain("hong@example.com");
    }

    @Test
    @DisplayName("전 필드가 빈 값(Worker 결손 형태)이면 resumeContext를 생략한다")
    void allEmptyWorkerShapeOmitsResumeContext() {
        StructuredData emptyShape = new StructuredData(
                new StructuredData.Profile("", ""), List.of(), List.of(), List.of());

        String metadata = assembler.assemble(7, InterviewType.THIRTY_MIN, Position.BACKEND, emptyShape);

        assertThat(metadata).doesNotContain("resumeContext");
    }

    @Test
    @DisplayName("resumeContext 포함 기준은 유형이 아니라 이력서 데이터 — FIVE_MIN이라도 데이터가 있으면 포함한다")
    void fiveMinWithResumeDataIncludesContext() {
        StructuredData data = new StructuredData(null,
                List.of(new StructuredData.Skill("언어", List.of("Java"))), null, null);

        String metadata = assembler.assemble(9, InterviewType.FIVE_MIN, Position.BACKEND, data);

        assertThat(metadata).contains("\"resumeContext\":\"[기술 스택]\\n- 언어: Java\"");
    }

    @Test
    @DisplayName("부분 결손(null 갈래 — PATCH 유래): 결손 조각만 빠지고 존재 섹션만 남는다")
    void partialNullFieldsDropOnlyMissingPieces() {
        StructuredData data = new StructuredData(null, null,
                List.of(new StructuredData.Project("Kkori", null, "설명", null)), null);

        String metadata = assembler.assemble(9, InterviewType.THIRTY_MIN, Position.BACKEND, data);

        assertThat(resumeContextOf(metadata)).isEqualTo("[프로젝트]\\n- Kkori: 설명");
    }

    @Test
    @DisplayName("선두 필드 결손: 콜론 없이 나머지로 불릿을 구성하고, name 없는 project의 role은 함께 버린다")
    void missingLeadFieldBuildsBulletWithoutColon() {
        StructuredData data = new StructuredData(null,
                List.of(new StructuredData.Skill("", List.of("Java", "Python"))),
                List.of(new StructuredData.Project("", "백엔드", "설명만 있는 프로젝트", List.of("Spring"))),
                List.of(new StructuredData.Experience("", "제목 없는 경험"),
                        new StructuredData.Experience("인턴", "")));

        String metadata = assembler.assemble(9, InterviewType.THIRTY_MIN, Position.BACKEND, data);

        assertThat(resumeContextOf(metadata)).isEqualTo(
                "[기술 스택]\\n- Java, Python"
                        + "\\n\\n[프로젝트]\\n- 설명만 있는 프로젝트 (기술: Spring)"
                        + "\\n\\n[경험]\\n- 제목 없는 경험\\n- 인턴");
    }

    @Test
    @DisplayName("정규화: 개행·탭·연속 공백은 공백 1개로 접히고, 공백만 값·배열 내 빈 문자열은 결손 처리된다")
    void normalizationCollapsesWhitespaceAndDropsBlanks() {
        StructuredData data = new StructuredData(null,
                List.of(new StructuredData.Skill("언어", List.of("Java", "", "   "))),
                List.of(new StructuredData.Project("Kkori", "   ",
                        "줄1\r\n줄2\n\n\t줄3   끝", List.of())),
                null);

        String metadata = assembler.assemble(9, InterviewType.THIRTY_MIN, Position.BACKEND, data);

        assertThat(resumeContextOf(metadata)).isEqualTo(
                "[기술 스택]\\n- 언어: Java\\n\\n[프로젝트]\\n- Kkori: 줄1 줄2 줄3 끝");
    }

    @Test
    @DisplayName("꼬리만 남는 항목(techStacks만 있는 project)은 항목째 생략된다")
    void tailOnlyItemIsDroppedEntirely() {
        StructuredData data = new StructuredData(null,
                List.of(new StructuredData.Skill("언어", List.of("Java"))),
                List.of(new StructuredData.Project("", "", "   ", List.of("Spring"))),
                null);

        String metadata = assembler.assemble(9, InterviewType.THIRTY_MIN, Position.BACKEND, data);

        assertThat(resumeContextOf(metadata)).isEqualTo("[기술 스택]\\n- 언어: Java");
    }

    @Test
    @DisplayName("직렬화 실패는 S004가 아니라 C001로 던진다 — S004는 LiveKit 호출 실패 전용")
    void serializationFailureMapsToInternalError() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any()))
                .thenThrow(InvalidDefinitionException.from((com.fasterxml.jackson.core.JsonGenerator) null,
                        "직렬화 불가", (com.fasterxml.jackson.databind.JavaType) null));
        DispatchMetadataAssembler brokenAssembler = new DispatchMetadataAssembler(failing);

        assertThatThrownBy(() -> brokenAssembler.assemble(1, InterviewType.FIVE_MIN, Position.BACKEND, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /** 직렬화된 metadata에서 resumeContext의 JSON 문자열 값(이스케이프 형태 그대로)을 뽑는다. */
    private static String resumeContextOf(String metadata) {
        String marker = "\"resumeContext\":\"";
        int start = metadata.indexOf(marker);
        assertThat(start).as("resumeContext 필드 존재").isGreaterThanOrEqualTo(0);
        return metadata.substring(start + marker.length(), metadata.length() - 2);
    }
}
