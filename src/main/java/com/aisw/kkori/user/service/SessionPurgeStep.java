package com.aisw.kkori.user.service;

import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.repositoryservice.SessionRepositoryService;
import com.aisw.kkori.session.service.SessionRoomManager;
import com.aisw.kkori.session.service.UserSessionTerminator;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 세션 파기 단계 (PRD deletion.md 기능 3 — 2. 세션).
 *
 * <p>(1) non-terminal 잔존 세션의 방어적 정리(선기록 후 룸 삭제 — 기능 1이 처리했어야 하는 모순),
 * (2) 녹음 객체 삭제 후 포인터 NULL(세션마다 S3 → DB 순), (3) 대본 마스킹(에이전트 소유 테이블, 행 유지) +
 * 세션 soft delete(행 유지)를 user 잠금 트랜잭션 하나에서. {@code interview_metrics}·에이전트 Redis 사본은
 * 대상이 아니다(PRD 파기 대상 표).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPurgeStep implements PurgeStep {

    private final SessionRepositoryService sessionRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final UserSessionTerminator sessionTerminator;
    private final SessionRoomManager roomManager;
    private final S3Template s3Template;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public String key() {
        return PurgeDetail.STEP_SESSIONS;
    }

    @Override
    public int order() {
        return ORDER_SESSIONS;
    }

    @Override
    public PurgeDetail.StepResult execute(PurgeTarget target) {
        long userId = target.userId();
        List<InterviewSession> sessions = sessionRepositoryService.findAllByUserId(userId);
        if (sessions.isEmpty()) {
            return new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, 0, null, null, 0, 0, 0);
        }

        // 1) 잔존 non-terminal 세션 — ABORTED 선기록 후 룸 삭제(배치 스레드라 동기 삭제)
        List<String> leftoverRooms = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(userId);
            return sessionTerminator.abortAllForWithdrawal(userId, now());
        });
        if (!leftoverRooms.isEmpty()) {
            log.warn("파기 시점에 non-terminal 세션 잔존 — ABORTED 선기록 후 룸 삭제 (userId={}, count={})",
                    userId, leftoverRooms.size());
            leftoverRooms.forEach(roomManager::deleteRoomQuietly);
        }

        // 2) 녹음 — S3 삭제(잠금 밖) 후 포인터 NULL(잠금 하). 재시도 시 이미 NULL이면 건너뛴다
        int recordings = 0;
        for (InterviewSession session : sessions) {
            if (session.getRecordingObjectKey() == null) {
                continue;
            }
            s3Template.deleteObject(session.getRecordingBucket(), session.getRecordingObjectKey());
            transactionTemplate.executeWithoutResult(status -> {
                userRepositoryService.lockUser(userId);
                sessionRepositoryService.clearRecording(session.getId(), now());
            });
            recordings++;
        }

        // 3) 대본 마스킹 + 세션 soft delete — 한 트랜잭션
        List<Long> ids = sessions.stream().map(InterviewSession::getId).toList();
        int masked = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(userId);
            Instant now = now();
            int count = sessionRepositoryService.maskTranscripts(ids, now);
            sessionRepositoryService.softDeleteAllByUserId(userId, now);
            return count;
        });
        log.info("세션 파기 (userId={}, rows={}, transcripts={}, recordings={}, abortedLeftovers={})",
                userId, sessions.size(), masked, recordings, leftoverRooms.size());
        return new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, sessions.size(), null, null,
                masked, recordings, leftoverRooms.size());
    }

    /** 트랜잭션 시각 — 잠금 획득 후 취득(공통: 시각 처리), 마이크로초 절삭. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
