package com.aisw.kkori.global.livekit;

import com.aisw.kkori.session.service.SessionTicket;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LiveKit 토큰 발급의 페이로드/TTL/권한 검증 (서명 검증만, Cloud 접속 없음).
 *
 * <p>grant·TTL 단언은 HBB1-256 검증 기준의 회귀 유지분이다. identity는 HBB1-18에서
 * 세션 파생 신원({@code candidate-{sessionId}})으로 개정되어, 어댑터는 전달받은 값을
 * 그대로 서명하는지만 확인한다.
 *
 * <p>LiveKit JVM SDK 0.14.0의 {@code toJwt()}는 {@code iat}를 넣지 않고 {@code exp}만
 * {@code currentTimeMillis()+ttl}로 계산하므로, TTL은 발급 직전·직후 시각으로 범위 검증한다.
 */
class LiveKitTokenIssuerTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-at-least-thirty-two-bytes-long";
    private static final String URL = "wss://test.invalid";

    private LiveKitTokenIssuer issuer(Duration ttl) {
        return new LiveKitTokenIssuer(new LiveKitProperties(URL, API_KEY, API_SECRET, ttl, Duration.ofSeconds(3)));
    }

    /** 발급된 JWT를 HS256 서명으로 파싱한다 (LiveKit AccessToken은 apiSecret으로 HS256 서명). */
    private Claims parse(String jwt) {
        SecretKeySpec key = new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> videoGrant(Claims claims) {
        return (Map<String, Object>) claims.get("video");
    }

    @Test
    void identityIsPassedThroughVerbatim() {
        SessionTicket ticket = issuer(Duration.ofHours(1)).issue("candidate-42", "room-abc");

        Claims claims = parse(ticket.token());
        assertThat(claims.getSubject()).isEqualTo("candidate-42");
        assertThat(ticket.serverUrl()).isEqualTo(URL);
    }

    @SuppressWarnings("unchecked")
    @Test
    void grantsAudioOnlyPublish() {
        SessionTicket ticket = issuer(Duration.ofHours(1)).issue("candidate-7", "room-xyz");

        Map<String, Object> video = videoGrant(parse(ticket.token()));
        assertThat(video.get("roomJoin")).isEqualTo(true);
        assertThat(video.get("room")).isEqualTo("room-xyz");
        assertThat(video.get("canPublish")).isEqualTo(true);
        assertThat(video.get("canSubscribe")).isEqualTo(true);
        assertThat((List<String>) video.get("canPublishSources")).containsExactly("microphone");
        assertThat(video.get("canPublishData")).isEqualTo(false);
    }

    @Test
    void expReflectsConfiguredTtl() {
        Duration ttl = Duration.ofMinutes(30);

        Instant before = Instant.now();
        SessionTicket ticket = issuer(ttl).issue("candidate-1", "room-1");
        Instant after = Instant.now();

        Instant exp = parse(ticket.token()).getExpiration().toInstant();
        // JWT exp는 초 단위 절삭이라 1초 여유를 둔다.
        assertThat(exp).isBetween(before.plus(ttl).minusSeconds(1), after.plus(ttl).plusSeconds(1));
    }

    @Test
    void eachIssueUsesGivenRoomName() {
        LiveKitTokenIssuer issuer = issuer(Duration.ofHours(1));

        String roomA = (String) videoGrant(parse(issuer.issue("candidate-1", "room-A").token())).get("room");
        String roomB = (String) videoGrant(parse(issuer.issue("candidate-1", "room-B").token())).get("room");
        assertThat(roomA).isEqualTo("room-A");
        assertThat(roomB).isEqualTo("room-B");
    }
}
