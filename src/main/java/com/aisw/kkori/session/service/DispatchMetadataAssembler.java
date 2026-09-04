package com.aisw.kkori.session.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.StructuredData;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.dto.DispatchMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 디스패치 metadata 조립 (agent-dispatch.md 기능 1) — 세션 속성과 이력서
 * {@code structured_data}에서 metadata JSON 문자열을 만든다. 순수 코드 조립(LLM·외부 호출 없음).
 *
 * <p>산출 자구는 양 레포가 공유하는 계약 픽스처로 검증된다 — 정규화·조각 결합 규칙은 PRD가
 * 재량 없이 결정하며, 여기 구현은 그 규칙의 옮김이다. {@code resumeContext}에 {@code profile}
 * (이름·이메일)은 어떤 경우에도 넣지 않는다(개인정보 최소화).
 *
 * <p>직렬화 실패는 디스패치 이전의 내부 오류이므로 C001로 던진다 — S004는 LiveKit 디스패치
 * 호출 실패 전용이다(PRD 에러 코드). 호출은 세션 생성 트랜잭션 안이라 실패 시 전체 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class DispatchMetadataAssembler {

    private final ObjectMapper objectMapper;

    /** metadata JSON 문자열을 조립한다 — {@code structuredData}는 이력서 없는 세션이면 null. */
    public String assemble(long sessionId, InterviewType interviewType, Position position,
                           StructuredData structuredData) {
        DispatchMetadata metadata = new DispatchMetadata(
                String.valueOf(sessionId), interviewType.name(), position.name(),
                resumeContext(structuredData));
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** 섹션 3개(기술 스택·프로젝트·경험)를 빈 줄로 연결한다 — 전부 결손이면 null(필드 생략). */
    private String resumeContext(StructuredData data) {
        if (data == null) {
            return null;
        }
        List<String> sections = new ArrayList<>();
        addSection(sections, "[기술 스택]", skillBullets(data.skills()));
        addSection(sections, "[프로젝트]", projectBullets(data.projects()));
        addSection(sections, "[경험]", experienceBullets(data.experiences()));
        return sections.isEmpty() ? null : String.join("\n\n", sections);
    }

    private void addSection(List<String> sections, String header, List<String> bullets) {
        if (!bullets.isEmpty()) {
            sections.add(header + "\n" + String.join("\n", bullets));
        }
    }

    private List<String> skillBullets(List<StructuredData.Skill> skills) {
        return bullets(skills, skill -> bulletOf(
                normalize(skill.category()), joined(skill.items()), null));
    }

    private List<String> projectBullets(List<StructuredData.Project> projects) {
        return bullets(projects, project -> {
            String name = normalize(project.name());
            String role = normalize(project.role());
            // role은 name의 수식이다 — name 결손 시 role만 남기지 않고 함께 버린다 (PRD 조각 결합 표)
            String lead = name == null ? null : role == null ? name : name + " (" + role + ")";
            String stacks = joined(project.techStacks());
            String tail = stacks == null ? null : " (기술: " + stacks + ")";
            return bulletOf(lead, normalize(project.description()), tail);
        });
    }

    private List<String> experienceBullets(List<StructuredData.Experience> experiences) {
        return bullets(experiences, experience -> bulletOf(
                normalize(experience.title()), normalize(experience.description()), null));
    }

    private <T> List<String> bullets(List<T> items, java.util.function.Function<T, String> toBullet) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(toBullet)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 조각 결합: 선두부·본문이 모두 있으면 콜론으로 잇고, 한쪽만 있으면 그것만 쓴다.
     * 꼬리는 선두부·본문 중 하나 이상 있을 때만 붙인다 — 꼬리만 남으면 항목째 생략(null).
     */
    private String bulletOf(String lead, String body, String tail) {
        String core;
        if (lead != null && body != null) {
            core = lead + ": " + body;
        } else if (lead != null) {
            core = lead;
        } else if (body != null) {
            core = body;
        } else {
            return null;
        }
        return "- " + core + (tail == null ? "" : tail);
    }

    /** 배열 정규화 후 ", "로 연결 — 남는 요소가 없으면 null(결손). */
    private String joined(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> normalized = values.stream()
                .map(this::normalize)
                .filter(Objects::nonNull)
                .toList();
        return normalized.isEmpty() ? null : String.join(", ", normalized);
    }

    /**
     * 문자열 정규화(PRD): 개행·탭을 공백으로 치환하고 연속 공백을 1개로 통합, 앞뒤 공백 제거.
     * 결과가 비면 결손(null) — 공백만 있는 값 포함. description의 원문 보존 개행이 불릿·섹션
     * 구조(줄 단위 파싱 가능성)를 깨지 않게 하는 규칙이다.
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }
}
