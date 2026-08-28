package com.aisw.kkori.session.service;

import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

/**
 * 세션 전이의 공용 실행 관용구 — user 행 잠금 트랜잭션 + 잠금 후 시각 취득.
 *
 * <p>webhook 핸들러·스위퍼의 모든 전이가 이 관용구를 공유한다(PRD — 전이 경로의 user 잠금
 * 선행). 잠금은 <b>활성 재확인 없는</b> {@code findWithLockById}다 — 전이는 유저 상태와 무관한
 * 세션 수렴이 목적이라 탈퇴 유저의 잔존 세션도 전이시킨다(생성 경로의 {@code findActiveWithLock}과
 * 의도적으로 다르다). 유저 행 부재(도달 불가 — soft delete만 존재)는 잠금 없이 진행한다.
 */
@Component
@RequiredArgsConstructor
public class SessionTransitionExecutor {

    private final UserRepositoryService userRepositoryService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 잠금 트랜잭션 안에서 전이를 실행하고 영향 행 수를 반환한다(시각은 잠금 획득 후 취득). */
    public int execute(Long userId, Function<Instant, Integer> transition) {
        Integer updated = transactionTemplate.execute(status -> {
            userRepositoryService.findWithLockById(userId);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            return transition.apply(now);
        });
        return updated == null ? 0 : updated;
    }
}
