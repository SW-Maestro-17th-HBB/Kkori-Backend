package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.config.S3Properties;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisMode;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.domain.ResumeAnalysisStatus;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.dto.ResumeUploadResponse;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 이력서 업로드 오케스트레이션 (docs/requirements/resume/resume.md §1).
 *
 * <p>검증 → 해시 계산 → 중복 조회(있으면 무부수효과 조기 반환) → S3 저장(없을 때만)
 * → DB 저장(Resume + 분석 상태) + 분석 요청 발행 → 응답.
 * S3 업로드는 외부 시스템이라 롤백이 불가능하므로 트랜잭션 밖에서 선행하고,
 * DB 저장과 이벤트 발행만 하나의 트랜잭션으로 묶는다. 발행 실패 시 DB는 롤백되고
 * S3 객체만 남지만, 해시 기반 키라 다음 업로드에서 재사용된다.
 *
 * <p>분석 요청 전달은 {@link ResumeAnalysisRequester}의 2단계 계약을 따른다 — 트랜잭션 안
 * 발행(비동기 모드) / 커밋 후 워커 HTTP 호출(동기 모드, HBB1-327 부하 테스트 실험).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final PdfValidator pdfValidator;
    private final S3Template s3Template;
    private final ResumeRepositoryService resumeRepositoryService;
    private final ResumeAnalysisRequester analysisRequester;
    private final TransactionTemplate transactionTemplate;
    private final S3Properties s3Properties;

    public ResumeUploadResponse upload(Long userId, MultipartFile file, String title) {
        int pageCount = pdfValidator.validate(file);

        // 사용자별 해시 기반 objectKey — 같은 사용자의 같은 바이너리는 S3에 1부만 존재하고,
        // 소유권 경계가 키에 드러나 삭제 시 참조 확인도 사용자 내로 한정된다.
        String fileHash = sha256Hex(file);

        // 중복 업로드: 같은 사용자의 같은 해시 활성 이력서가 있으면 아무것도 바꾸지 않고 기존 정보만 반환한다.
        // 분석 상태와 무관한 단일 규칙 — FAILED 복구도 재분석 API의 몫이지 업로드의 부수효과가 아니다 (PRD §1).
        // 조회 범위는 반드시 (userId + fileHash) — 해시만으로 조회하면 타 사용자의 이력서가 노출된다.
        var existing = resumeRepositoryService.findDuplicate(userId, fileHash);
        if (existing.isPresent()) {
            Resume duplicate = existing.get();
            return ResumeUploadResponse.duplicated(duplicate, currentStatusOf(duplicate));
        }

        String objectKey = "resumes/" + userId + "/" + fileHash + ".pdf";
        uploadToS3IfAbsent(file, objectKey);

        String resolvedTitle = StringUtils.hasText(title) ? title : file.getOriginalFilename();

        try {
            UploadOutcome outcome = transactionTemplate.execute(tx -> {
                Resume resume = resumeRepositoryService.save(Resume.builder()
                        .userId(userId)
                        .title(resolvedTitle)
                        .fileHash(fileHash)
                        .originalFileBucket(s3Properties.bucket())
                        .originalFileKey(objectKey)
                        .originalFileName(file.getOriginalFilename())
                        .fileSize(file.getSize())
                        .mimeType(file.getContentType())
                        .pageCount(pageCount)
                        .build());
                ResumeAnalysisStatus status = resumeRepositoryService.saveStatus(ResumeAnalysisStatus.init(resume));

                ResumeParseRequestedMessage message = new ResumeParseRequestedMessage(
                        resume.getId(), userId, s3Properties.bucket(), objectKey, AnalysisMode.FULL);
                analysisRequester.dispatchInTransaction(message);

                return new UploadOutcome(ResumeUploadResponse.created(resume, status.getParseStatus()), message);
            });
            analysisRequester.dispatchAfterCommit(outcome.message());
            return outcome.response();
        } catch (DataIntegrityViolationException e) {
            // 동시 중복 업로드 레이스: 중복 조회는 둘 다 통과했지만 부분 유니크 인덱스
            // (ux_resumes_active_user_file_hash)가 최종 심판 — 진 쪽은 먼저 들어간 레코드를 반환한다.
            return resumeRepositoryService.findDuplicate(userId, fileHash)
                    .map(winner -> ResumeUploadResponse.duplicated(winner, currentStatusOf(winner)))
                    .orElseThrow(() -> e);   // 해시 충돌이 아닌 다른 무결성 위반이면 그대로 전파
        }
    }

    private AnalysisStatus currentStatusOf(Resume resume) {
        return resumeRepositoryService.getCurrentParseStatus(resume.getId());
    }

    /** 트랜잭션 블록의 결과 — 커밋 후 디스패치(dispatchAfterCommit)에 쓸 메시지를 블록 밖으로 나른다. */
    private record UploadOutcome(ResumeUploadResponse response, ResumeParseRequestedMessage message) {
    }

    /**
     * 같은 키의 객체가 없을 때만 저장한다 — 재업로드·고아 파일(이전 업로드에서 DB 저장 전
     * 서버 사망으로 S3에만 남은 객체) 모두 기존 객체를 재사용하게 된다.
     */
    private void uploadToS3IfAbsent(MultipartFile file, String objectKey) {
        try (InputStream inputStream = file.getInputStream()) {
            if (s3Template.objectExists(s3Properties.bucket(), objectKey)) {
                return;
            }
            ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType(file.getContentType())
                    .build();
            s3Template.upload(s3Properties.bucket(), objectKey, inputStream, metadata);
        } catch (IOException | RuntimeException e) {
            log.error("S3 업로드 실패: key={}", objectKey, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private String sha256Hex(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(file.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | IOException e) {
            // SHA-256 미지원 JVM은 없으므로 사실상 IOException(파일 읽기 실패)만 해당
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }
}
