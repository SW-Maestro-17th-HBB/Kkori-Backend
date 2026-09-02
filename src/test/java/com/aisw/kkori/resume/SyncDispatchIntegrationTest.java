package com.aisw.kkori.resume;

import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.resume.domain.AnalysisStatus;
import com.aisw.kkori.resume.domain.Resume;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import io.awspring.cloud.s3.S3Template;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 동기 디스패치 모드 통합 검증 (HBB1-327) — MockWebServer가 AI 워커를 연기한다.
 *
 * <p>검증 핵심: ① 응답 계약이 비동기 모드와 동일 ② 워커 호출은 트랜잭션 커밋 <b>후</b>
 * (워커가 요청을 받는 시점에 다른 커넥션에서 커밋된 상태가 보인다) ③ 스트림 무발행
 * ④ 워커 실패 시 R007 + FAILED 전이 + 기존 재분석 경로로 복구.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SyncDispatchIntegrationTest {

    /** 실제 저장된 유저 id — 재분석이 user 행 잠금을 잡으므로 가짜 상수로는 401이 난다 (ResumeParsedIntegrationTest 참조). */
    private long userId;

    private static MockWebServer workerServer;

    /** 워커 응답 코드·수신 횟수와, 워커가 요청을 받은 시점에 DB에서 관찰한 분석 상태.
     * workerServer.getRequestCount()는 static 서버라 테스트 간 누적되므로 횟수는 여기서 따로 센다. */
    private final AtomicInteger workerResponseCode = new AtomicInteger(200);
    private final AtomicInteger workerRequestCount = new AtomicInteger();
    private final AtomicReference<AnalysisStatus> statusSeenByWorker = new AtomicReference<>();

    @Autowired MockMvc mockMvc;
    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired UserRepository userRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired S3Template s3Template;

    @DynamicPropertySource
    static void syncDispatchProperties(DynamicPropertyRegistry registry) throws IOException {
        workerServer = new MockWebServer();
        workerServer.start();
        registry.add("app.ai-dispatch.mode", () -> "sync");
        registry.add("app.ai-dispatch.worker-base-url", () -> workerServer.url("/").toString());
        registry.add("app.ai-dispatch.sync-read-timeout", () -> "5s");
    }

    @AfterAll
    static void shutdownWorker() throws IOException {
        workerServer.shutdown();
    }

    @BeforeEach
    void setUp() {
        if (!s3Template.bucketExists(TestcontainersConfiguration.TEST_BUCKET)) {
            s3Template.createBucket(TestcontainersConfiguration.TEST_BUCKET);
        }
        statusRepository.deleteAll();
        resumeRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.delete(ResumeParseRequestedMessage.STREAM_KEY);
        userId = userRepository.save(User.create("kakao-sync-1", "sync@example.com", "동기")).getId();

        workerResponseCode.set(200);
        workerRequestCount.set(0);
        statusSeenByWorker.set(null);
        workerServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                workerRequestCount.incrementAndGet();
                // 워커 스레드에서 별도 커넥션으로 읽는다 — 여기서 상태가 보이면 커밋 후 호출이 증명된다
                statusRepository.findAll().stream().findFirst()
                        .ifPresent(s -> statusSeenByWorker.set(s.getParseStatus()));
                return new MockResponse().setResponseCode(workerResponseCode.get());
            }
        });
    }

    private static RequestPostProcessor authOf(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "resume.pdf", "application/pdf", ResumePdfFixtures.pdfWithPages(1));
    }

    @Test
    @DisplayName("동기 모드 업로드: 응답 계약 동일, 워커 호출은 커밋 후, 스트림 무발행")
    void syncUpload_callsWorkerAfterCommit_withoutStreamPublish() throws Exception {
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile())
                        .param("title", "동기 모드 업로드")
                        .with(authOf(userId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));

        // 워커가 요청을 받은 시점에 이미 커밋된 UPLOADED 상태가 (다른 커넥션에서) 보였다
        assertThat(statusSeenByWorker.get()).isEqualTo(AnalysisStatus.UPLOADED);

        assertThat(workerRequestCount.get()).isEqualTo(1);
        assertThat(redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded())).isEmpty();
    }

    @Test
    @DisplayName("워커 실패 시 R007 + 행 FAILED, 이후 재분석으로 복구된다 (스트림은 끝까지 무발행)")
    void workerFailure_marksFailed_thenReanalyzeRecovers() throws Exception {
        workerResponseCode.set(500);
        mockMvc.perform(multipart("/api/v1/resumes")
                        .file(pdfFile())
                        .with(authOf(userId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("R007"));

        Resume resume = resumeRepository.findAll().get(0);
        assertThat(statusRepository.findByResumeId(resume.getId()))
                .hasValueSatisfying(s -> assertThat(s.getParseStatus()).isEqualTo(AnalysisStatus.FAILED));

        // FAILED가 됐으므로 기존 복구 경로(재분석)가 그대로 열린다
        workerResponseCode.set(200);
        mockMvc.perform(post("/api/v1/resumes/{resumeId}/reanalyze", resume.getId())
                        .with(authOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("UPLOADED"));   // FAILED → FULL 재시작

        assertThat(workerRequestCount.get()).isEqualTo(2);
        assertThat(redisTemplate.opsForStream()
                .range(ResumeParseRequestedMessage.STREAM_KEY, Range.unbounded())).isEmpty();
    }
}
