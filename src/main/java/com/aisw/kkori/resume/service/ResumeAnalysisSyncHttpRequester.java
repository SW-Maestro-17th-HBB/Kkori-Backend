package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;

/**
 * {@link ResumeAnalysisRequester}의 동기 구현 — 워커 HTTP를 호출하고 분석 완료까지 블로킹한다.
 * 부하 테스트 비교 실험용(HBB1-327, docs/experiments/sync-dispatch.md).
 *
 * <p>호출은 반드시 트랜잭션 커밋 후에 일어나야 한다 — 워커 처리(수십 초)를 기다리는 동안
 * DB 커넥션·잠금을 쥐면 부하 테스트가 커넥션 풀 고갈을 측정하게 된다. 가드가 이를 강제한다.
 *
 * <p>실패 처리: 호출 시점엔 상태 변경(UPLOADED/EMBEDDING)이 이미 커밋돼 있어, 실패를 방치하면
 * 행이 영구 진행 중으로 남는다(재분석 API가 진행 중을 거부해 복구 불가). 그래서 실패 시
 * 새 트랜잭션으로 FAILED 전이를 커밋한 뒤 기존 에러 코드로 던진다 — 복구는 기존 재분석 경로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai-dispatch.mode", havingValue = "sync")
public class ResumeAnalysisSyncHttpRequester implements ResumeAnalysisRequester {

    static final String ANALYZE_PATH = "/internal/analyses/resume";

    private final RestClient aiWorkerRestClient;
    private final TransactionTemplate transactionTemplate;
    private final ResumeRepositoryService resumeRepositoryService;
    private final Clock clock;

    @Override
    public void dispatchInTransaction(ResumeParseRequestedMessage message) {
        // 동기 모드는 트랜잭션 안에서 할 일이 없다 — 호출은 커밋 후(dispatchAfterCommit)
    }

    @Override
    public void dispatchAfterCommit(ResumeParseRequestedMessage message) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "동기 워커 호출은 트랜잭션 밖에서만 허용된다 — 호출 위치가 잘못됐다 (resumeId=%d)"
                            .formatted(message.resumeId()));
        }
        try {
            aiWorkerRestClient.post()
                    .uri(ANALYZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("동기 분석 호출 실패: resumeId={}", message.resumeId(), e);
            markFailed(message.resumeId());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);
        }
    }

    /** 실패를 새 트랜잭션으로 확정한다. 워커가 그 사이 종단 상태(EMBEDDED/FAILED)를 썼다면 건드리지 않는다. */
    private void markFailed(Long resumeId) {
        Boolean transitioned = transactionTemplate.execute(tx ->
                resumeRepositoryService.failAnalysis(resumeId, "동기 분석 호출 실패", clock.instant()));
        if (!Boolean.TRUE.equals(transitioned)) {
            log.warn("FAILED 전이 생략 — 이미 종단 상태이거나 행 없음: resumeId={}", resumeId);
        }
    }
}
