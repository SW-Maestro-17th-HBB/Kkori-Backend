package com.aisw.kkori.user.service;

import com.aisw.kkori.global.config.S3Properties;
import com.aisw.kkori.resume.repositoryservice.JdbcResumePurger;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 이력서 파기 단계 (PRD deletion.md 기능 3 — 1. 이력서).
 *
 * <p>S3를 먼저 지우고 DB 포인터를 나중에 지운다 — 실패 시 재시도 재료(행)가 남는다. 행이 가리키는 객체에
 * 더해 설정 버킷의 prefix {@code resumes/{userId}/} 잔여 객체(업로드 중 서버 사망으로 남은 고아)까지
 * 목록 조회로 지운다. 키가 사용자별이라 공유 키 참조 확인은 불필요하다. DB 삭제(청크 → 분석 상태 → 행)는
 * user 잠금 트랜잭션 하나에서 수행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumePurgeStep implements PurgeStep {

    static final String KEY_PREFIX = "resumes/";

    private final ResumeRepositoryService resumeRepositoryService;
    private final UserRepositoryService userRepositoryService;
    private final S3Template s3Template;
    private final S3Properties s3Properties;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String key() {
        return PurgeDetail.STEP_RESUMES;
    }

    @Override
    public int order() {
        return ORDER_RESUMES;
    }

    @Override
    public PurgeDetail.StepResult execute(PurgeTarget target) {
        List<JdbcResumePurger.ObjectRef> refs = resumeRepositoryService.findPurgeTargetsByUserId(target.userId());

        // 1) S3 — 행 참조 객체 + prefix 잔여 객체 (잠금·트랜잭션 밖, 없는 키 삭제는 성공으로 응답)
        Set<String> deleted = new LinkedHashSet<>();
        for (JdbcResumePurger.ObjectRef ref : refs) {
            s3Template.deleteObject(ref.bucket(), ref.key());
            deleted.add(ref.bucket() + "/" + ref.key());
        }
        String prefix = KEY_PREFIX + target.userId() + "/";
        String bucket = s3Properties.bucket();
        for (S3Resource resource : s3Template.listObjects(bucket, prefix)) {
            String objectKey = resource.getLocation().getObject();
            if (objectKey == null || !objectKey.startsWith(prefix)) {
                continue; // 방어 — prefix 밖 키는 이 유저의 것이 아니다
            }
            s3Template.deleteObject(bucket, objectKey);
            deleted.add(bucket + "/" + objectKey);
        }

        // 2) DB — 청크(Worker 소유) → 분석 상태 → 이력서 행, user 잠금 하 한 트랜잭션
        List<Long> ids = refs.stream().map(JdbcResumePurger.ObjectRef::resumeId).toList();
        JdbcResumePurger.PurgeCounts counts = transactionTemplate.execute(status -> {
            userRepositoryService.lockUser(target.userId());
            return resumeRepositoryService.purgeByIds(ids);
        });
        log.info("이력서 파기 (userId={}, rows={}, chunks={}, s3Objects={})",
                target.userId(), counts.rows(), counts.chunks(), deleted.size());
        return new PurgeDetail.StepResult(PurgeDetail.StepResult.DONE, counts.rows(), counts.chunks(),
                deleted.size(), null, null, null);
    }
}
