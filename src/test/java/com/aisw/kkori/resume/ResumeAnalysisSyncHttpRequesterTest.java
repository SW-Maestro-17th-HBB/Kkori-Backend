package com.aisw.kkori.resume;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.resume.domain.AnalysisMode;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repositoryservice.ResumeRepositoryService;
import com.aisw.kkori.resume.service.ResumeAnalysisSyncHttpRequester;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ResumeAnalysisSyncHttpRequester}의 HTTP 계약 검증 — MockWebServer로 AI 워커를 연기한다
 * ({@code LiveKitAgentDispatcherTest}와 동일 구조). 요청 경로·JSON 바디와 실패 시
 * FAILED 전이 + R007 매핑은 이 테스트가 유일한 단위 검증 지점이다.
 */
class ResumeAnalysisSyncHttpRequesterTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final ResumeParseRequestedMessage MESSAGE =
            new ResumeParseRequestedMessage(1L, 2L, "kkori-resumes", "resumes/2/abc.pdf", AnalysisMode.FULL);

    private MockWebServer server;
    private ResumeRepositoryService resumeRepositoryService;
    private ResumeAnalysisSyncHttpRequester requester;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(SHORT_TIMEOUT)
                                .withReadTimeout(SHORT_TIMEOUT)))
                .build();

        resumeRepositoryService = mock(ResumeRepositoryService.class);
        when(resumeRepositoryService.failAnalysis(anyLong(), anyString(), any())).thenReturn(true);

        // 트랜잭션 매니저는 모킹 — execute()가 콜백만 실행하고 커밋은 no-op이 된다
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        requester = new ResumeAnalysisSyncHttpRequester(
                restClient, transactionTemplate, resumeRepositoryService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("성공: 워커 경로에 스트림 메시지와 동일한 5개 필드를 JSON으로 보내고, FAILED 전이는 없다")
    void dispatch_sendsContractBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertThatCode(() -> requester.dispatchAfterCommit(MESSAGE)).doesNotThrowAnyException();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/internal/analyses/resume");
        JsonNode body = new ObjectMapper().readTree(recorded.getBody().readUtf8());
        assertThat(body.get("resumeId").asLong()).isEqualTo(1L);
        assertThat(body.get("userId").asLong()).isEqualTo(2L);
        assertThat(body.get("bucket").asText()).isEqualTo("kkori-resumes");
        assertThat(body.get("objectKey").asText()).isEqualTo("resumes/2/abc.pdf");
        assertThat(body.get("mode").asText()).isEqualTo("FULL");
        verifyNoInteractions(resumeRepositoryService);
    }

    @ParameterizedTest(name = "워커 {0} 응답")
    @ValueSource(ints = {500, 502, 503})
    @DisplayName("워커 5xx면 FAILED 전이 후 R007을 던진다")
    void workerError_marksFailedAndThrows(int statusCode) {
        server.enqueue(new MockResponse().setResponseCode(statusCode));

        assertThatThrownBy(() -> requester.dispatchAfterCommit(MESSAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);

        verify(resumeRepositoryService).failAnalysis(eq(1L), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("워커 무응답(read timeout)이어도 FAILED 전이 후 R007을 던진다")
    void workerTimeout_marksFailedAndThrows() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> requester.dispatchAfterCommit(MESSAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);

        verify(resumeRepositoryService).failAnalysis(eq(1L), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("전이가 생략돼도(이미 종단 상태) R007은 그대로 던진다")
    void transitionSkipped_stillThrows() {
        when(resumeRepositoryService.failAnalysis(anyLong(), anyString(), any())).thenReturn(false);
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> requester.dispatchAfterCommit(MESSAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESUME_ANALYSIS_REQUEST_FAILED);
    }
}
