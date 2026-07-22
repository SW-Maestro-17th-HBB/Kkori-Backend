package com.aisw.kkori.user.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.user.config.ConsentPolicyProperties;
import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.dto.ConsentCatalogResponse;
import com.aisw.kkori.user.dto.ConsentChangeRequest;
import com.aisw.kkori.user.dto.UserConsentsResponse;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 동의 항목·상태 조회와 선택 동의 변경 (PRD {@code docs/requirements/user/consent.md} 기능 2~4).
 *
 * <p>변경은 유저 상태를 쓰는 다른 경로(수정·탈퇴·복구)와 user 행 잠금을 직렬화 지점으로
 * 공유한다(account.md 기능 2의 직렬화 계약). 시각은 잠금 획득 후 취득한다(공통: 시각 처리).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final ConsentPolicyProperties consentPolicyProperties;
    private final Clock clock;

    /** 현재 동의 항목·버전 제공 — 설정만 읽으므로 트랜잭션·DB 접근이 없다(공개 경로). */
    public ConsentCatalogResponse getCatalog() {
        return ConsentCatalogResponse.from(consentPolicyProperties);
    }

    /** 동의 상태 조회 — 유형별 최신 행 기준. 읽기 전용이라 잠금은 불필요하다. */
    @Transactional(readOnly = true)
    public UserConsentsResponse getMyConsents(Long userId) {
        requireActive(userId);
        return UserConsentsResponse.from(latestByType(userId));
    }

    /**
     * 선택 동의 변경 (PRD 기능 4 — 한 트랜잭션에서 이 순서대로).
     *
     * <p>판정표: {@code agreed=false}는 최신 상태가 미동의면 no-op, {@code AGREED}면 그 행과
     * 동일 버전으로 {@code WITHDRAWN} append. {@code agreed=true}는 미동의 상태거나 기록 버전이
     * 제출 버전보다 낮으면(새 버전 재동의) append, 같으면 no-op(멱등), 높으면 no-op + WARN
     * (버전 단조 증가 위반 신호 — 구버전 AGREED가 최신 행이 되는 역행을 흡수).
     */
    @Transactional
    public UserConsentsResponse change(Long userId, String rawType, ConsentChangeRequest request) {
        // 1) 잠금이 필요 없는 검증을 잠금 앞에 배치 (도메인 코드가 명시된 검증 — 서비스 계층)
        ConsentType type = ConsentType.fromValue(rawType)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CONSENT_TYPE));
        if (type.isRequired()) {
            throw new BusinessException(ErrorCode.CONSENT_NOT_CHANGEABLE);
        }
        boolean agreed = request.agreed();
        if (agreed) {
            if (request.version() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
            }
            if (request.version() != consentPolicyProperties.versionOf(type)) {
                throw new BusinessException(ErrorCode.CONSENT_VERSION_MISMATCH);
            }
        }

        // 2) user 행 잠금 + 활성 재확인 — 탈퇴의 일괄 WITHDRAWN과의 경합 차단(직렬화 계약 공유).
        //    잠금 없이 append하면 탈퇴가 최신 상태를 읽은 뒤 이 AGREED가 커밋되는 순서에서
        //    탈퇴 완료 후 최신 상태가 AGREED로 남는 정합 붕괴가 생긴다.
        userRepository.findActiveWithLock(userId);

        // 3) 트랜잭션 시각 — 잠금 획득 후 취득(시각 역행 방지 — 공통: 시각 처리).
        //    마이크로초 절삭은 timestamptz(6) 반올림과의 정합(updatedAt 동등성).
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 4) 최신 상태 판정 — 잠금 후에 조회해야 직전 커밋(동시 탈퇴의 WITHDRAWN 등)이 보인다
        Map<ConsentType, UserConsent> latestByType = latestByType(userId);
        UserConsent latest = latestByType.get(type);

        // 5) 상태가 실제로 바뀌는 경우에만 append — 연속 중복 행 방지(멱등)
        if (agreed) {
            int submitted = request.version();
            if (latest == null || latest.getAction() == ConsentAction.WITHDRAWN
                    || latest.getVersion() < submitted) {
                latestByType.put(type, userConsentRepository.save(
                        UserConsent.create(userId, type, true, submitted, now)));
            } else if (latest.getVersion() > submitted) {
                log.warn("동의 버전 역행 감지 — 설정 롤백 여부 확인 필요. userId={}, type={}, 기록 버전={}, 설정 버전={}",
                        userId, type, latest.getVersion(), submitted);
            }
        } else if (latest != null && latest.getAction() == ConsentAction.AGREED) {
            // 철회는 철회 대상 최신 AGREED와 동일 버전으로 기록한다 — 요청 version은 사용하지 않는다
            latestByType.put(type, userConsentRepository.save(
                    UserConsent.create(userId, type, false, latest.getVersion(), now)));
        }

        // 6) 조회와 동일 형식으로 전체 최신 상태 반환
        return UserConsentsResponse.from(latestByType);
    }

    /** JWT 필터가 차단하지만 방어적으로 재확인한다(account.md 기능 1과 동일 기준). */
    private void requireActive(Long userId) {
        userRepository.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    private Map<ConsentType, UserConsent> latestByType(Long userId) {
        return userConsentRepository.findLatestByUserId(userId).stream()
                .collect(Collectors.toMap(UserConsent::getConsentType, Function.identity()));
    }
}
