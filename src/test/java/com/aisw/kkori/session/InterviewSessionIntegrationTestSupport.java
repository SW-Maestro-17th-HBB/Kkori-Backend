package com.aisw.kkori.session;

import com.aisw.kkori.ResumeSeeder;
import com.aisw.kkori.TestcontainersConfiguration;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;
import com.aisw.kkori.resume.repository.ResumeAnalysisStatusRepository;
import com.aisw.kkori.resume.repository.ResumeRepository;
import com.aisw.kkori.session.domain.InterviewSession;
import com.aisw.kkori.session.domain.InterviewType;
import com.aisw.kkori.session.domain.Position;
import com.aisw.kkori.session.domain.SessionStatus;
import com.aisw.kkori.session.repository.InterviewSessionRepository;
import com.aisw.kkori.session.service.SessionRoomManager;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 면접 세션 통합 테스트 공통 베이스.
 *
 * <p>{@link SessionRoomManager}는 모킹한다 — 테스트 프로퍼티의 LiveKit 값은 연결 불가한
 * 더미라 실제 Server API 왕복이 불가능하다(토큰 서명은 로컬 연산이라 실물 유지). 하위
 * 클래스의 {@code @MockitoBean} 구성을 동일하게 유지해야 ApplicationContext가 공유된다
 * (Testcontainers 비용).
 *
 * <p>EMBEDDED·FAILED 등 Worker 소유 상태와 세션 상태 전이는 JdbcTemplate로 연기한다 —
 * ACTIVE 계열 전이는 후속 스토리(webhook) 전까지 Spring 코드로 만들 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class InterviewSessionIntegrationTestSupport {

    static final String SESSIONS_URI = "/api/v1/sessions";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired ResumeRepository resumeRepository;
    @Autowired ResumeAnalysisStatusRepository statusRepository;
    @Autowired InterviewSessionRepository sessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired StringRedisTemplate redisTemplate;

    @MockitoBean SessionRoomManager roomManager;

    @BeforeEach
    void cleanDatabase() {
        sessionRepository.deleteAll();
        statusRepository.deleteAll();
        resumeRepository.deleteAll();
        userRepository.deleteAll();
        // 동시성 테스트의 reanalyze 경로가 분석 요청을 발행한다 — resume 스위트와 공유하는 스트림이라 여기서도 정리
        redisTemplate.delete(ResumeParseRequestedMessage.STREAM_KEY);
    }

    long saveUser(String providerId) {
        return userRepository.save(User.create(providerId, providerId + "@example.com", "테스터")).getId();
    }

    String bearerOf(long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    String createBody(Long resumeId, String interviewType, String position) {
        StringBuilder body = new StringBuilder("{");
        if (resumeId != null) {
            body.append("\"resumeId\": ").append(resumeId).append(", ");
        }
        if (interviewType != null) {
            body.append("\"interviewType\": \"").append(interviewType).append("\", ");
        }
        if (position != null) {
            body.append("\"position\": \"").append(position).append("\", ");
        }
        if (body.charAt(body.length() - 1) == ' ') {
            body.setLength(body.length() - 2);
        }
        return body.append("}").toString();
    }

    // ─── Worker·상태 연기 헬퍼 — 공용 픽스처({@link ResumeSeeder})에 위임 ───

    private ResumeSeeder resumeSeeder() {
        return new ResumeSeeder(resumeRepository, statusRepository, jdbcTemplate);
    }

    long embeddedResume(long userId) {
        return resumeSeeder().embedded(userId);
    }

    long failedResume(long userId) {
        return resumeSeeder().failed(userId);
    }

    long inProgressResume(long userId) {
        return resumeSeeder().inProgress(userId);
    }

    /** 세션을 원하는 상태로 시딩 — PENDING 저장 후 필요 시 SQL로 상태를 전이한다(전이 코드는 후속 스토리 소관). */
    long sessionInStatus(long userId, Long resumeId, SessionStatus status, String roomName) {
        InterviewSession session = sessionRepository.save(InterviewSession.pending(
                userId, resumeId, InterviewType.THIRTY_MIN, Position.BACKEND, roomName));
        if (status != SessionStatus.PENDING) {
            jdbcTemplate.update("UPDATE interview_session SET status = ? WHERE id = ?",
                    status.name(), session.getId());
        }
        return session.getId();
    }

    String statusOfSession(long sessionId) {
        return jdbcTemplate.queryForObject("SELECT status FROM interview_session WHERE id = ?",
                String.class, sessionId);
    }
}
