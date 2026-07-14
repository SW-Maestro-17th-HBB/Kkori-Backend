package com.aisw.kkori.resume;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import io.awspring.cloud.s3.S3Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeUploadIntegrationTest {

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

    private MockMultipartFile pdfFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    @Test
    @DisplayName("정상 업로드 시 201과 함께 resumeId·pageCount·UPLOADED가 반환되고, DB·S3·Stream에 기록된다")
    void upload_success() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("backend_resume.pdf", ResumePdfFixtures.pdfWithPages(2)))
                        .param("title", "백엔드 개발자 이력서"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resumeId").isNumber())
                .andExpect(jsonPath("$.data.title").value("백엔드 개발자 이력서"))
                .andExpect(jsonPath("$.data.pageCount").value(2))
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));

        List<Resume> resumes = resumeRepository.findAll();
        assertThat(resumes).hasSize(1);
        Resume resume = resumes.get(0);

        // 분석 상태 레코드 UPLOADED로 생성
        assertThat(statusRepository.findByResumeId(resume.getId()))
                .hasValueSatisfying(s -> assertThat(s.getParseStatus()).isEqualTo(AnalysisStatus.UPLOADED));

        // S3 원본 저장
        assertThat(s3Template.objectExists(resume.getOriginalFileBucket(), resume.getOriginalFileKey())).isTrue();

        // 분석 요청 이벤트 1건 발행
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue())
                .containsEntry("resumeId", String.valueOf(resume.getId()))
                .containsEntry("objectKey", resume.getOriginalFileKey());
    }

    @Test
    @DisplayName("title 미지정 시 원본 파일명이 title로 사용된다")
    void upload_withoutTitle_usesOriginalFileName() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("my_resume.pdf", ResumePdfFixtures.pdfWithPages(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("my_resume.pdf"));
    }

    @Test
    @DisplayName("동일 파일 재업로드 시(파일명 달라도) 새 레코드 없이 기존 정보 + duplicated=true가 200으로 반환된다")
    void upload_sameFileTwice_returnsExistingAsDuplicated() throws Exception {
        byte[] samePdf = ResumePdfFixtures.pdfWithPages(1);

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", samePdf)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicated").value(false));

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("final_resume.pdf", samePdf)))   // 이름이 달라도 내용이 같으면 같은 해시
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
    @DisplayName("S3에 객체만 있고 레코드가 없으면(고아) 재저장 없이 기존 객체를 가리키는 새 레코드가 생성된다")
    void upload_orphanObject_isReused() throws Exception {
        byte[] pdf = ResumePdfFixtures.pdfWithPages(1);
        String fileHash = sha256Hex(pdf);
        String objectKey = "resumes/" + fileHash + ".pdf";

        // 고아 상태 재현: 이전 업로드에서 S3 저장 후 DB 저장 전에 서버가 죽은 상황
        s3Template.upload(TestcontainersConfiguration.TEST_BUCKET, objectKey,
                new java.io.ByteArrayInputStream(pdf));

        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("resume.pdf", pdf)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicated").value(false));  // DB에 없었으니 신규

        List<Resume> resumes = resumeRepository.findAll();
        assertThat(resumes).hasSize(1);
        assertThat(resumes.get(0).getOriginalFileKey()).isEqualTo(objectKey);  // 기존 객체 재사용
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("활성 이력서의 file_hash에는 부분 유니크 인덱스가 걸린다 — soft delete된 해시는 재사용 가능")
    void fileHash_partialUniqueIndex_allowsReuseAfterSoftDelete() {
        Resume first = resumeRepository.saveAndFlush(resumeWithHash("samehash"));

        // 같은 해시의 활성 레코드 중복 → 인덱스가 차단 (동시 업로드 레이스의 최종 방어선)
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resumeRepository.saveAndFlush(resumeWithHash("samehash")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // soft delete 후에는 같은 해시로 새 레코드 생성 가능 (부분 조건 WHERE deleted_at IS NULL)
        first.softDelete();
        resumeRepository.saveAndFlush(first);
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> resumeRepository.saveAndFlush(resumeWithHash("samehash")))
                .doesNotThrowAnyException();
    }

    private Resume resumeWithHash(String fileHash) {
        return Resume.builder()
                .title("t")
                .fileHash(fileHash)
                .originalFileBucket(TestcontainersConfiguration.TEST_BUCKET)
                .originalFileKey("resumes/" + fileHash + ".pdf")
                .originalFileName("t.pdf")
                .fileSize(1L)
                .mimeType("application/pdf")
                .pageCount(1)
                .build();
    }

    @Test
    @DisplayName("파일 없이 요청하면 400 FILE_REQUIRED")
    void upload_withoutFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("R001"));
    }

    @Test
    @DisplayName("PDF가 아닌 파일이면 400 INVALID_FILE_TYPE")
    void upload_nonPdf_returns400() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/v1/resumes").file(txt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R002"));
    }

    @Test
    @DisplayName("10MB 초과 파일이면 413 FILE_TOO_LARGE")
    void upload_oversized_returns413() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("big.pdf", ResumePdfFixtures.oversizedBytes())))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("R003"));
    }

    @Test
    @DisplayName("손상된 PDF면 400 INVALID_PDF")
    void upload_corruptedPdf_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("broken.pdf", ResumePdfFixtures.corruptedPdf())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R004"));
    }

    @Test
    @DisplayName("10페이지 초과 PDF면 400 PAGE_LIMIT_EXCEEDED")
    void upload_tooManyPages_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("long.pdf", ResumePdfFixtures.pdfWithPages(11))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("R005"));
    }

    @Test
    @DisplayName("검증 실패 시 DB·S3·Stream에 아무것도 남지 않는다")
    void upload_validationFailure_leavesNothing() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile("long.pdf", ResumePdfFixtures.pdfWithPages(11))))
                .andExpect(status().isBadRequest());

        assertThat(resumeRepository.findAll()).isEmpty();
        assertThat(statusRepository.findAll()).isEmpty();
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded());
        assertThat(records).isEmpty();
    }
}
