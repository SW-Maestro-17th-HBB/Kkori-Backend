package com.aisw.kkori.user;

import com.aisw.kkori.auth.AuthIntegrationTestSupport;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.dto.ConsentChangeRequest;
import com.aisw.kkori.user.service.ConsentService;
import com.aisw.kkori.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static com.aisw.kkori.ConcurrencyTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선택 동의 변경의 동시성 검증 (PRD {@code docs/requirements/user/consent.md} 기능 4 검증 기준).
 *
 * <p>반대 목표 시나리오는 어느 스레드가 먼저 user 잠금을 얻을지 가정하지 않는다 — 행 수·action
 * 순서로 실제 직렬화 순서를 판별한 뒤 그 순서와 결과의 정합만 검증한다. 기록 시각은 마이크로초
 * 해상도에서 동률일 수 있으므로 id 순서에 대해 비감소(>=)로 단언한다.
 */
class ConsentChangeConcurrencyTest extends AuthIntegrationTestSupport {

    private static final int ITERATIONS = 10;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private UserService userService;

    @Test
    @DisplayName("변경과 탈퇴가 동시에 실행돼도 탈퇴 완료 후 marketing 최신 상태가 AGREED로 남지 않고 기록 시각이 역행하지 않는다")
    void changeVersusWithdrawKeepsInvariant() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            User user = seededUser("kakao-9100-" + i);
            Long userId = user.getId();

            runConcurrently(
                    () -> {
                        try {
                            consentService.change(userId, "marketing", new ConsentChangeRequest(true, 1));
                        } catch (BusinessException e) {
                            // 탈퇴가 먼저 커밋되면 잠금 후 활성 재확인에서 거부된다
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                        }
                    },
                    () -> userService.withdraw(userId));

            assertThat(userRepository.findById(userId).orElseThrow().isDeleted()).isTrue();
            List<UserConsent> marketing = rowsOf(userId, ConsentType.MARKETING);
            if (!marketing.isEmpty()) {
                // 변경이 먼저였다면 탈퇴의 일괄 WITHDRAWN이 그 AGREED까지 철회했어야 한다
                assertThat(marketing.get(marketing.size() - 1).getAction()).isEqualTo(ConsentAction.WITHDRAWN);
            }
            assertCreatedAtNonDecreasingById(userId);
        }
    }

    @Test
    @DisplayName("동일 목표 상태의 동시 변경 두 건은 이력을 1건만 생성한다")
    void sameTargetConcurrentChangesAppendOnce() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            User user = saveUser("kakao-9200-" + i);
            Long userId = user.getId();
            Runnable agree = () -> consentService.change(userId, "marketing", new ConsentChangeRequest(true, 1));

            runConcurrently(agree, agree);

            assertThat(rowsOf(userId, ConsentType.MARKETING))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getAction()).isEqualTo(ConsentAction.AGREED));
        }
    }

    @Test
    @DisplayName("반대 목표 상태의 동시 변경은 잠금 순서대로 직렬 처리되고 최종 상태가 나중에 처리된 요청과 일치한다")
    void oppositeTargetConcurrentChangesSerialize() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            User user = saveUser("kakao-9300-" + i);
            Long userId = user.getId();

            runConcurrently(
                    () -> consentService.change(userId, "marketing", new ConsentChangeRequest(true, 1)),
                    () -> consentService.change(userId, "marketing", new ConsentChangeRequest(false, null)));

            List<UserConsent> marketing = rowsOf(userId, ConsentType.MARKETING);
            // 이력 없음 시작이므로: 철회 선처리(no-op) → 동의 = 1행 AGREED, 동의 선처리 → 철회 = 2행 [AGREED, WITHDRAWN]
            if (marketing.size() == 1) {
                assertThat(marketing.get(0).getAction()).isEqualTo(ConsentAction.AGREED);
            } else {
                assertThat(marketing).hasSize(2)
                        .extracting(UserConsent::getAction)
                        .containsExactly(ConsentAction.AGREED, ConsentAction.WITHDRAWN);
            }
            assertCreatedAtNonDecreasingById(userId);
        }
    }

    // ── 헬퍼 ──

    /** 가입 상태 재현 — 필수 3종 AGREED (탈퇴가 철회를 append할 대상). */
    private User seededUser(String providerId) {
        User user = saveUser(providerId);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.PRIVACY, true, 1, now));
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.AUDIO_USAGE, true, 1, now));
        userConsentRepository.save(UserConsent.create(user.getId(), ConsentType.RESUME_USAGE, true, 1, now));
        return user;
    }

    private List<UserConsent> rowsOf(Long userId, ConsentType type) {
        return userConsentRepository.findByUserId(userId).stream()
                .filter(consent -> consent.getConsentType() == type)
                .sorted(Comparator.comparing(UserConsent::getId))
                .toList();
    }

    /** 잠금 후 시각 취득이 보장하는 성질 — id 순서(커밋 순서)에 대해 기록 시각이 과거로 가지 않는다. */
    private void assertCreatedAtNonDecreasingById(Long userId) {
        List<UserConsent> all = userConsentRepository.findByUserId(userId).stream()
                .sorted(Comparator.comparing(UserConsent::getId))
                .toList();
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).getCreatedAt())
                    .isAfterOrEqualTo(all.get(i - 1).getCreatedAt());
        }
    }

}
