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
 * <p>대상은 soft delete 후 지연이 지났고 분석이 terminal이거나 보류 상한까지 지난 이력서다. terminal 조건은
 * 진행 중인 Worker가 삭제 뒤에 청크(이력서 본문)를 INSERT해 고아로 남기는 것을 막고, 상한은 Worker가 상태를
 * 영영 전이하지 않는 병리에서도 삭제가 수렴하게 하는 백스톱이다(그 경우의 고아 청크는 수용 잔여 위험).
 *
 * <p><b>업로드 경로와의 직렬화</b>: 같은 사용자·같은 해시의 재업로드는 같은 S3 키를 재사용한다(해시 기반 키).
 * "활성 참조 확인 → S3 삭제" 사이에 재업로드가 끼어들면 새 행이 사라진 객체를 가리키므로, 참조 확인·S3 삭제·행
 * 삭제를 <b>user 잠금 트랜잭션 하나</b>에서 수행한다 — 이 단계에 한해 S3 삭제가 잠금 안에 있다(단일 호출이라
 * 짧다). 업로드는 같은 잠금 안에서 객체 존재를 재확인해 없으면 다시 저장한다({@code ResumeUploadService}).
 * S3 삭제 실패는 트랜잭션째 되돌아가 행이 남고 다음 회차에 다시 후보가 된다. 건별로 격리하며, 탈퇴 파기와
 * 같은 이력서를 두고 겹쳐도 둘 다 멱등이라 무해하다.
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

    /** user 잠금 하 한 트랜잭션: 공유 키 참조 확인 → S3 삭제(참조 없을 때만) → DB(청크·상태·행). */
    private void delete(JdbcResumePurger.Candidate candidate) {
        Outcome outcome = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(candidate.userId());
            boolean objectShared = resumeRepositoryService.existsActiveDuplicate(candidate.userId(), candidate.fileHash());
            if (!objectShared) {
                s3Template.deleteObject(candidate.bucket(), candidate.key());
            }
            return new Outcome(objectShared, resumeRepositoryService.purgeByIds(List.of(candidate.resumeId())));
        });
        if (outcome.objectShared()) {
            log.info("이력서 물리 삭제 — 같은 키를 공유하는 활성 이력서가 있어 S3 객체 유지 (resumeId={}, userId={})",
                    candidate.resumeId(), candidate.userId());
        }
        log.info("이력서 물리 삭제 (resumeId={}, userId={}, rows={}, chunks={}, s3Deleted={})",
                candidate.resumeId(), candidate.userId(), outcome.counts().rows(), outcome.counts().chunks(),
                !outcome.objectShared());
    }

    private record Outcome(boolean objectShared, JdbcResumePurger.PurgeCounts counts) {
    }
}
