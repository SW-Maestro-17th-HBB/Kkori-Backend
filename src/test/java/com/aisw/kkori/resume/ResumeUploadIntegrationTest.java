package com.aisw.kkori.resume;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeUploadIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired S3Template s3Template;

    @BeforeEach
    void setUp() {
        if (!s3Template.bucketExists(TestcontainersConfiguration.TEST_BUCKET)) {
            s3Template.createBucket(TestcontainersConfiguration.TEST_BUCKET);
        }
        statusRepository.deleteAll();
        resumeRepository.deleteAll();
        redisTemplate.delete(ResumeParseRequestedMessage.STREAM_KEY);
    }

    /** JwtAuthenticationFilter가 principal에 심는 것과 동일한 형태(Long)로 인증을 주입한다. */
    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private MockMultipartFile pdfFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    @Test
    @DisplayName("정상 업로드 시 201과 함께 resumeId·pageCount·UPLOADED가 반환되고, DB·S3·Stream에 기록된다")
    void upload_success() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("backend_resume.pdf", ResumePdfFixtures.pdfWithPages(2)))
                        .param("title", "백엔드 개발자 이력서")
                        .with(authOf(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resumeId").isNumber())
                .andExpect(jsonPath("$.data.title").value("백엔드 개발자 이력서"))
                .andExpect(jsonPath("$.data.pageCount").value(2))
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));

        List<Resume> resumes = resumeRepository.findAll();
        assertThat(resumes).hasSize(1);
        Resume resume = resumes.get(0);
        assertThat(resume.getUserId()).isEqualTo(USER_ID);

        // 분석 상태 레코드 UPLOADED로 생성
        assertThat(statusRepository.findByResumeId(resume.getId()))
                .hasValueSatisfying(s -> assertThat(s.getParseStatus()).isEqualTo(AnalysisStatus.UPLOADED));

        // S3 원본 저장
        assertThat(s3Template.objectExists(resume.getOriginalFileBucket(), resume.getOriginalFileKey())).isTrue();

        // 분석 요청 이벤트 1건 발행 — userId 포함 (Worker가 상태 이벤트에 에코할 값)
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .containsEntry("resumeId", String.valueOf(resume.getId()))
                .containsEntry("userId", String.valueOf(USER_ID))
                .containsEntry("objectKey", resume.getOriginalFileKey())
                .containsEntry("mode", "FULL");   // Worker 계약: 신규 업로드는 항상 FULL
    }

    @Test
    @DisplayName("title 미지정 시 원본 파일명이 title로 사용된다")
    void upload_withoutTitle_usesOriginalFileName() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("my_resume.pdf", ResumePdfFixtures.pdfWithPages(1)))
                        .with(authOf(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("my_resume.pdf"));
    }

    @Test
    @DisplayName("동일 파일 재업로드 시(파일명 달라도) 새 레코드 없이 기존 정보 + duplicated=true가 200으로 반환된다")
    void upload_sameFileTwice_returnsExistingAsDuplicated() throws Exception {
        byte[] samePdf = ResumePdfFixtures.pdfWithPages(1);

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", samePdf))
                        .with(authOf(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicated").value(false));

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("final_resume.pdf", samePdf))   // 이름이 달라도 내용이 같으면 같은 해시
                        .with(authOf(USER_ID)))
                .andExpect(status().isOk())                            // 생성 아님 → 200
                .andExpect(jsonPath("$.data.duplicated").value(true))
                .andExpect(jsonPath("$.data.title").value("resume.pdf"))          // 기존 이력서의 정보
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));  // 상태도 변경되지 않음

        // 레코드·분석 요청 모두 1개 — 중복 업로드는 아무것도 만들지 않는다
        assertThat(resumeRepository.findAll()).hasSize(1);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
        assertThat(records).hasSize(1);
    }

    @Test
    @DisplayName("다른 사용자가 같은 파일을 업로드하면 중복이 아니라 신규로 처리된다 (dedup 범위는 사용자별)")
    void upload_sameFileByDifferentUser_isNotDuplicated() throws Exception {
        byte[] samePdf = ResumePdfFixtures.pdfWithPages(1);

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", samePdf))
                        .with(authOf(USER_ID)))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", samePdf))
                        .with(authOf(OTHER_USER_ID)))
                .andExpect(status().isCreated())                          // 타인의 이력서는 중복 판정 대상 아님
                .andExpect(jsonPath("$.data.duplicated").value(false));

        List<Resume> resumes = resumeRepository.findAll();
        assertThat(resumes).hasSize(2);
        // objectKey에 userId 세그먼트가 있어 서로 다른 객체를 가리킨다 (소유권 경계)
        assertThat(resumes.get(0).getOriginalFileKey()).isNotEqualTo(resumes.get(1).getOriginalFileKey());
    }

    @Test
    @DisplayName("S3에 객체만 있고 레코드가 없으면(고아) 재저장 없이 기존 객체를 가리키는 새 레코드가 생성된다")
    void upload_orphanObject_isReused() throws Exception {
        byte[] pdf = ResumePdfFixtures.pdfWithPages(1);
        String objectKey = "resumes/" + USER_ID + "/" + sha256Hex(pdf) + ".pdf";

        // 고아 상태 재현: 이전 업로드에서 S3 저장 후 DB 저장 전에 서버가 죽은 상황
        s3Template.upload(TestcontainersConfiguration.TEST_BUCKET, objectKey, new ByteArrayInputStream(pdf));

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", pdf))
                        .with(authOf(USER_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicated").value(false));  // DB에 없었으니 신규

        List<Resume> resumes = resumeRepository.findAll();
        assertThat(resumes).hasSize(1);
        assertThat(resumes.get(0).getOriginalFileKey()).isEqualTo(objectKey);  // 기존 객체 재사용
    }

    @Test
    @DisplayName("활성 이력서의 (user_id, file_hash)에는 부분 유니크 인덱스가 걸린다 — 타 사용자·soft delete는 허용")
    void fileHash_partialUniqueIndex_allowsOtherUserAndReuseAfterSoftDelete() {
        Resume first = resumeRepository.saveAndFlush(resumeWithHash(USER_ID, "samehash"));

        // 같은 사용자·같은 해시의 활성 레코드 중복 → 인덱스가 차단 (동시 업로드 레이스의 최종 방어선)
        assertThatThrownBy(() -> resumeRepository.saveAndFlush(resumeWithHash(USER_ID, "samehash")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 다른 사용자는 같은 해시라도 활성 레코드 생성 가능 (복합 인덱스 — dedup 범위는 사용자별)
        assertThatCode(() -> resumeRepository.saveAndFlush(resumeWithHash(OTHER_USER_ID, "samehash")))
                .doesNotThrowAnyException();

        // soft delete 후에는 같은 사용자·같은 해시로 새 레코드 생성 가능 (부분 조건 WHERE deleted_at IS NULL)
        first.softDelete();
        resumeRepository.saveAndFlush(first);
        assertThatCode(() -> resumeRepository.saveAndFlush(resumeWithHash(USER_ID, "samehash")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("파일 없이 요청하면 400 FILE_REQUIRED")
    void upload_withoutFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes").with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("R001"));
    }

    @Test
    @DisplayName("PDF가 아닌 파일이면 400 INVALID_FILE_TYPE")
    void upload_nonPdf_returns400() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/v1/resumes").file(txt).with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R002"));
    }

    @Test
    @DisplayName("10MB 초과 파일이면 413 FILE_TOO_LARGE")
    void upload_oversized_returns413() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("big.pdf", ResumePdfFixtures.oversizedBytes()))
                        .with(authOf(USER_ID)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("R003"));
    }

    @Test
    @DisplayName("손상된 PDF면 400 INVALID_PDF")
    void upload_corruptedPdf_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("broken.pdf", ResumePdfFixtures.corruptedPdf()))
                        .with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R004"));
    }

    @Test
    @DisplayName("10페이지 초과 PDF면 400 PAGE_LIMIT_EXCEEDED")
    void upload_tooManyPages_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("long.pdf", ResumePdfFixtures.pdfWithPages(11)))
                        .with(authOf(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R005"));
    }

    @Test
    @DisplayName("검증 실패 시 DB·S3·Stream에 아무것도 남지 않는다")
    void upload_validationFailure_leavesNothing() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("long.pdf", ResumePdfFixtures.pdfWithPages(11)))
                        .with(authOf(USER_ID)))
                .andExpect(status().isBadRequest());

        assertThat(resumeRepository.findAll()).isEmpty();
        assertThat(statusRepository.findAll()).isEmpty();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
        assertThat(records).isEmpty();
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Resume resumeWithHash(long userId, String fileHash) {
        return Resume.builder()
                .userId(userId)
                .title("t")
                .fileHash(fileHash)
                .originalFileBucket(TestcontainersConfiguration.TEST_BUCKET)
                .originalFileKey("resumes/" + userId + "/" + fileHash + ".pdf")
                .originalFileName("t.pdf")
                .fileSize(1L)
                .mimeType("application/pdf")
                .pageCount(1)
                .build();
    }
}
