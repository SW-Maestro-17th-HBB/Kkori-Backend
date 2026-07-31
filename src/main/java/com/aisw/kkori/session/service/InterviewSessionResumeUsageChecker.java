package com.aisw.kkori.session.service;

import com.aisw.kkori.resume.service.ResumeUsageChecker;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ResumeUsageChecker}의 세션 도메인 구현 — "사용 중" = 해당 이력서를 참조하는
 * non-terminal 세션의 존재 (interview-session-creation.md 기능 1, 이력서 사용 중 차단).
 */
@Component
@RequiredArgsConstructor
public class InterviewSessionResumeUsageChecker implements ResumeUsageChecker {

    private final InterviewSessionRepository sessionRepository;

    @Override
    public boolean isInUse(Long resumeId) {
        return sessionRepository.existsByResumeIdAndStatusIn(resumeId, SessionStatus.NON_TERMINAL);
    }
}
