package com.aisw.kkori.resume.service;

import com.aisw.kkori.resume.config.ResumePhysicalDeleteProperties;
import com.aisw.kkori.resume.repositoryservice.JdbcResumePurger;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 개별 삭제(soft delete) 이력서의 물리 삭제 배치 (PRD deletion.md 기능 5 — resume.md §5 위임).
 *
 * <p>대상은 soft delete 후 지연이 지났고 분석이 terminal이거나 보류 상한까지 지난 이력서다. 지연은 업로드
 * 경로("S3 객체 존재 확인 → DB 저장")와 배치의 S3 삭제가 겹치는 창을 흡수하고, terminal 조건은 진행 중인
 * Worker가 삭제 뒤에 청크(이력서 본문)를 INSERT해 고아로 남기는 것을 막는다. 상한은 Worker가 상태를 영영
 * 전이하지 않는 병리에서도 삭제가 수렴하게 하는 백스톱이다(그 경우의 고아 청크는 수용 잔여 위험).
 *
 * <p>같은 사용자·같은 해시의 활성 이력서가 있으면 S3 객체는 남긴다(해시 기반 키를 여러 행이 공유). 건별로
 * 격리하며, 실패한 건은 행이 남아 다음 회차에 다시 후보가 된다. 탈퇴 파기와 같은 이력서를 두고 겹쳐도 둘 다
 * 멱등이라 무해하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePhysicalDeleteService {

    /**
     * 분석 진행 중 이력서의 보류 상한 = 지연 × 이 배수 (코드 상수 — 튜닝 노브가 아닌 안전 백스톱,
     * 세션 스위퍼의 대조 상한 배수 선례).
     */
    static final int STALE_ANALYSIS_CEILING_MULTIPLIER = 6;

    private final ResumeRepositoryService resumeRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final S3Template s3Template;
    private final ResumePhysicalDeleteProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public void runCycle() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Duration delay = properties.physicalDeleteDelay();
        Instant delayCutoff = now.minus(delay);
        Instant ceilingCutoff = now.minus(delay.multipliedBy(STALE_ANALYSIS_CEILING_MULTIPLIER));
        for (JdbcResumePurger.Candidate candidate
                : resumeRepositoryService.findPhysicalDeleteCandidates(delayCutoff, ceilingCutoff)) {
            try {
                delete(candidate);
            } catch (RuntimeException e) {
                log.warn("이력서 물리 삭제 실패 — 다음 회차 재시도 (resumeId={}, userId={}): {}",
                        candidate.resumeId(), candidate.userId(), e.getClass().getSimpleName());
            }
        }
    }

    /** S3(공유 키 참조 확인, 잠금 밖) → DB(청크·상태·행, user 잠금 하 한 트랜잭션). */
    private void delete(JdbcResumePurger.Candidate candidate) {
        boolean objectShared = resumeRepositoryService.existsActiveDuplicate(candidate.userId(), candidate.fileHash());
        if (objectShared) {
            log.info("이력서 물리 삭제 — 같은 키를 공유하는 활성 이력서가 있어 S3 객체 유지 (resumeId={}, userId={})",
                    candidate.resumeId(), candidate.userId());
        } else {
            s3Template.deleteObject(candidate.bucket(), candidate.key());
        }
        JdbcResumePurger.PurgeCounts counts = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(candidate.userId());
            return resumeRepositoryService.purgeByIds(List.of(candidate.resumeId()));
        });
        log.info("이력서 물리 삭제 (resumeId={}, userId={}, rows={}, chunks={}, s3Deleted={})",
                candidate.resumeId(), candidate.userId(), counts.rows(), counts.chunks(), !objectShared);
    }
}
