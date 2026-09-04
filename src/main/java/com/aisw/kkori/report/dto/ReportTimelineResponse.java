package com.aisw.kkori.report.dto;

import com.aisw.kkori.report.domain.ReportFeedback;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 질문-답변 타임라인 응답 (PRD §4). 페이지네이션 없음 — 한 세션의 대본은 유한하다.
 */
public record ReportTimelineResponse(List<Item> items) {

    /**
     * 질문 1개의 타임라인 항목. questionType·parentQuestionNumber는 대본 값 그대로 전달
     * — 꼬리 소속 표시("꼬리 Q1")는 프론트가 parentQuestionNumber로 그린다.
     */
    public record Item(
            int questionNumber,
            String questionType,
            Integer parentQuestionNumber,
            String question,
            String answer,
            Evaluation evaluation
    ) {
    }

    /** 답변 평가 — resume_context는 노출 형태 미정이라 포함하지 않는다(PRD §4). */
    public record Evaluation(
            Integer logicScore,
            Integer specificityScore,
            Integer technicalAccuracyScore,
            String feedback,
            List<String> weaknessTags
    ) {
        static Evaluation from(ReportFeedback feedback) {
            return new Evaluation(
                    feedback.getLogicScore(),
                    feedback.getSpecificityScore(),
                    feedback.getTechnicalAccuracyScore(),
                    feedback.getFeedback(),
                    feedback.getWeaknessTags()
            );
        }
    }

    /**
     * 대본 발화 + 답변별 평가 → 타임라인 조립.
     *
     * <p>같은 questionNumber의 면접관 발화는 질문으로, 사용자 발화는 답변으로 시간순 연결한다.
     * 항목 순서는 각 질문의 첫 발화 spokenAt 오름차순 — 문자열이 아닌 실제 시각으로 비교한다
     * (오프셋·소수점 자릿수가 다르면 문자열 순서가 시간순과 어긋난다. Worker와 동일 규칙).
     */
    public static ReportTimelineResponse of(List<TranscriptUtterance> utterances,
                                            List<ReportFeedback> feedbacks) {
        Map<Integer, Evaluation> evaluations = feedbacks.stream()
                .collect(Collectors.toMap(ReportFeedback::getQuestionNumber, Evaluation::from));

        Map<Integer, List<TranscriptUtterance>> byNumber = utterances.stream()
                .sorted(Comparator.comparing(u -> spokenInstant(u.spokenAt())))
                .collect(Collectors.groupingBy(TranscriptUtterance::questionNumber,
                        LinkedHashMap::new, Collectors.toList()));

        List<Item> items = byNumber.entrySet().stream()
                .map(entry -> toItem(entry.getKey(), entry.getValue(), evaluations.get(entry.getKey())))
                .toList();
        return new ReportTimelineResponse(items);
    }

    private static Item toItem(int questionNumber, List<TranscriptUtterance> group,
                               Evaluation evaluation) {
        TranscriptUtterance first = group.get(0);
        return new Item(
                questionNumber,
                first.questionType(),
                first.parentQuestionNumber(),
                joinContents(group, TranscriptUtterance.SPEAKER_INTERVIEWER),
                joinContents(group, TranscriptUtterance.SPEAKER_USER),
                evaluation
        );
    }

    private static String joinContents(List<TranscriptUtterance> group, String speaker) {
        return group.stream()
                .filter(u -> speaker.equals(u.speaker()))
                .map(TranscriptUtterance::content)
                .collect(Collectors.joining(" "));
    }

    private static Instant spokenInstant(String spokenAt) {
        return OffsetDateTime.parse(spokenAt).toInstant();
    }
}
