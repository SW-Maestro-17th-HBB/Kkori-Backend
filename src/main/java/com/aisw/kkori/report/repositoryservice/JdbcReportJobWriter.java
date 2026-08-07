package com.aisw.kkori.report.repositoryservice;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재생성 Job 기록 갱신 — Worker 소유 테이블(report_generation_jobs)이라 JPA 엔티티 없이
 * 네이티브로 쓴다(대본 {@link JdbcTranscriptReader}와 같은 최소 결합).
 *
 * <p>테이블은 Worker가 기동 시 멱등 생성한다(Kkori-AI worker/src/report/repository.py).
 * 재생성 시 Spring이 requested_at을 갱신한다는 분담은 Worker 쪽 DDL 주석에도 명시되어 있다.
 * retry_count·error_message는 Worker의 운영 기록이라 건드리지 않는다.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JdbcReportJobWriter {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Job의 requested_at을 현재 시각으로 갱신한다 (PRD §1 재생성).
     * Worker가 리포트와 Job을 한 트랜잭션으로 만들므로 FAILED 리포트에 Job이 없는 것은
     * 계약상 불가능한 상태다 — 0행 갱신이면 로그 후 500으로 변환한다.
     */
    public void updateRequestedAtToNow(long reportId) {
        int updated = jdbcTemplate.update(
                "UPDATE report_generation_jobs SET requested_at = now(), updated_at = now() "
                        + "WHERE report_id = ?", reportId);
        if (updated == 0) {
            log.error("재생성 대상 리포트의 Job 행이 없음 (report_id={})", reportId);
            throw new BusinessException(ErrorCode.REPORT_GENERATION_REQUEST_FAILED);
        }
    }
}
