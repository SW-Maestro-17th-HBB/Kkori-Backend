package com.aisw.kkori.user.service;

import com.aisw.kkori.global.config.S3Properties;
import com.aisw.kkori.resume.repositoryservice.JdbcResumePurger;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.user.domain.PurgeDetail;
import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이력서 파기 단계의 prefix 목록 페이지네이션 (PRD deletion.md 기능 3 검증 기준 — 1,000개 초과 고아 객체).
 * MinIO에 1,000개 넘게 올리는 대신 S3 클라이언트를 더블로 바꿔 두 페이지 응답을 재현한다.
 */
class ResumePurgeStepTest {

    @Test
    @DisplayName("prefix 목록이 여러 페이지면 continuation token으로 끝까지 조회해 전부 삭제하고 건수에 합산한다")
    void deletesOrphansAcrossAllPages() {
        ResumeRepositoryService resumeRepositoryService = mock(ResumeRepositoryService.class);
        when(resumeRepositoryService.findPurgeTargetsByUserId(7L)).thenReturn(List.of());
        when(resumeRepositoryService.purgeByIds(anyList())).thenReturn(new JdbcResumePurger.PurgeCounts(0, 0));
        UserRepositoryService userRepositoryService = mock(UserRepositoryService.class);
        S3Template s3Template = mock(S3Template.class);
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("resumes/7/a.pdf").build(),
                                S3Object.builder().key("resumes/7/b.pdf").build())
                        .isTruncated(true).nextContinuationToken("page-2").build())
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("resumes/7/c.pdf").build(),
                                S3Object.builder().key("other/7/x.pdf").build()) // prefix 밖 — 방어적으로 무시
                        .isTruncated(false).build());
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Object>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
        ResumePurgeStep step = new ResumePurgeStep(resumeRepositoryService, userRepositoryService, s3Template,
                s3Client, new S3Properties("bucket"), transactionTemplate);

        PurgeDetail.StepResult result = step.execute(new PurgeTarget(1L, 7L, Instant.EPOCH));

        ArgumentCaptor<ListObjectsV2Request> requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(2)).listObjectsV2(requests.capture());
        assertThat(requests.getAllValues().get(0).continuationToken()).isNull();
        assertThat(requests.getAllValues().get(1).continuationToken()).isEqualTo("page-2");
        assertThat(requests.getAllValues()).allSatisfy(request -> assertThat(request.prefix()).isEqualTo("resumes/7/"));
        verify(s3Template).deleteObject("bucket", "resumes/7/a.pdf");
        verify(s3Template).deleteObject("bucket", "resumes/7/b.pdf");
        verify(s3Template).deleteObject("bucket", "resumes/7/c.pdf");
        verify(s3Template, times(3)).deleteObject(any(), any());
        verify(userRepositoryService).lockUser(anyLong());
        assertThat(result.status()).isEqualTo(PurgeDetail.StepResult.DONE);
        assertThat(result.s3Objects()).isEqualTo(3);
    }
}
