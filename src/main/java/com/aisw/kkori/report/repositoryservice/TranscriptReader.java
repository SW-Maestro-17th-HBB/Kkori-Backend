package com.aisw.kkori.report.repositoryservice;

import com.aisw.kkori.report.dto.TranscriptUtterance;

import java.util.List;
import java.util.Optional;

/**
 * 세션 대본 읽기 — 리포트 도메인은 대본 테이블의 읽기 전용 소비자다(PRD §4).
 *
 * <p>테이블(INTERVIEW_TRANSCRIPT)의 소유·스키마 변경 권한은 면접 도메인·에이전트에 있어
 * 접근 방식(직접 매핑/네이티브 조회 등)은 팀 합의로 바뀔 수 있다 — 이 인터페이스 뒤로
 * 격리해 구현체 교체만으로 대응한다.
 */
public interface TranscriptReader {

    /** 세션의 발화 목록. 대본 행이 없으면 empty. */
    Optional<List<TranscriptUtterance>> findUtterances(long sessionId);
}
