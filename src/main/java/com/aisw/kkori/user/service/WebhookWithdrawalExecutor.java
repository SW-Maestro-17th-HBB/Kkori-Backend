package com.aisw.kkori.user.service;

import com.aisw.kkori.user.repositoryservice.UserRepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹훅 탈퇴 동기화 실행기 — 오케스트레이터({@link KakaoUnlinkWebhookService})와 분리된
 * 별도 빈이어야 {@code @Transactional} 프록시가 적용된다(같은 빈 내부 호출은 프록시 우회).
 *
 * <p>timeout은 {@code withdraw}의 user 행 잠금 획득이 경합으로 대기해도 카카오의
 * 3초 응답 계약을 지키기 위한 상한이다. {@code UserService.withdraw}는 REQUIRED로
 * 이 timeout 트랜잭션에 참여한다.
 */
@Component
@RequiredArgsConstructor
public class WebhookWithdrawalExecutor {

    private final UserRepositoryService userRepositoryService;
    private final UserService userService;

    /**
     * 활성 유저면 기능 3과 동일한 탈퇴 트랜잭션(soft delete + RT 폐기 + WITHDRAWN append
     * + deletion_log)을 수행한다. 미존재·이미 탈퇴는 no-op(멱등) — 동시 중복·API 탈퇴와의
     * 경합은 withdraw 내부 조건부 UPDATE의 영향 행 수 0으로 흡수된다.
     */
    @Transactional(timeout = 2)
    public Result withdrawIfActive(String providerId) {
        return userRepositoryService.findByProviderId(providerId)
                .map(user -> {
                    if (user.isDeleted()) {
                        return Result.ALREADY_WITHDRAWN;
                    }
                    userService.withdraw(user.getId());
                    return Result.WITHDRAWN;
                })
                .orElse(Result.NOT_FOUND);
    }

    public enum Result { WITHDRAWN, ALREADY_WITHDRAWN, NOT_FOUND }
}
