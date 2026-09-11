package com.aisw.kkori.auth.service;

import com.aisw.kkori.auth.repositoryservice.AuthRepositoryService;
import com.aisw.kkori.global.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Refresh Token 청소 배치 (PRD deletion.md 기능 6 — 설계 초안 ADR-013의 이행).
 *
 * <p>만료된 RT는 즉시, 폐기된 RT는 보존 기간(기본 2일 — Grace Period·재사용 탈취 감지 창) 경과 후 삭제한다.
 * 보존 기간이 지난 폐기 토큰의 재사용은 {@code RT_REUSE_DETECTED} 대신 {@code RT_NOT_FOUND}가 된다 —
 * 어차피 재로그인을 유도하는 결과라 수용한다. 회전 체인의 {@code replaced_by}는 해시 문자열이라 선행 토큰이
 * 삭제되어도 후속 토큰의 유효성에 영향이 없다. 잠금은 잡지 않는다 — 시각 조건 벌크 DELETE라 다중 인스턴스
 * 동시 실행은 늦은 쪽이 0행으로 수렴하고, 재발급의 RT 잠금과는 대기로만 만난다(폐기 직후 토큰은 보존 기간
 * 안이라 삭제 대상이 아니다). 탈퇴 유저의 RT는 파기 배치가 파기 시점에 전량 삭제하므로 이 배치는 일반 청소용이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private final AuthRepositoryService authRepositoryService;
    private final JwtProperties jwtProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public void runCycle() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant revokedCutoff = now.minus(jwtProperties.revokedRefreshTokenRetention());
        int[] deleted = transactionTemplate.execute(status -> new int[] {
                authRepositoryService.deleteExpiredTokens(now),
                authRepositoryService.deleteRevokedTokensBefore(revokedCutoff)
        });
        if (deleted != null && (deleted[0] > 0 || deleted[1] > 0)) {
            log.info("RT 청소 (expired={}, revokedBeforeRetention={})", deleted[0], deleted[1]);
        }
    }
}
