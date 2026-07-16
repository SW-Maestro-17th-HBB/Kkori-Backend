package com.aisw.kkori.auth.service;

import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.SignupRequest;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.global.oauth.KakaoOAuthClient;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.config.ConsentPolicyProperties;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import com.aisw.kkori.user.service.ConsentDecision;
import com.aisw.kkori.user.service.RestoreResult;
import com.aisw.kkori.user.service.UserService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인증 흐름 파사드 — 소셜 로그인·회원가입·재발급·로그아웃.
 *
 * {@link #kakaoLogin}은 의도적으로 트랜잭션이 없다: 카카오 HTTP 왕복(외부 통신) 동안
 * DB 커넥션을 붙잡지 않기 위해, DB 작업은 {@link TokenService}의 트랜잭션 메서드에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final UserService userService;
    private final ConsentPolicyProperties consentPolicyProperties;
    private final Clock clock;

    /** 카카오 인가 코드를 교환·조회한 뒤 신규/기존/복구를 판정해 응답한다. */
    public KakaoLoginResponse kakaoLogin(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_CODE);
        }
        KakaoUserInfo info = kakaoOAuthClient.authenticate(code);
        return tokenService.processKakaoLogin(info);
    }

    /**
     * 회원가입 완료 — signup token 검증, 필수 동의 확인 후 계정 생성(또는 복구)과
     * 동의 기록을 한 트랜잭션으로 처리하고 JWT를 발급한다.
     *
     * <p>이 메서드가 트랜잭션 소유자다. {@code UserService.restore}와
     * {@code TokenService.issueTokenPair}는 REQUIRED로 참여한다(REQUIRES_NEW 금지) —
     * 복구의 유예 만료 경로에서 식별정보 파기·신규 생성·JWT 저장이 원자적이어야 한다.
     * 토큰의 {@code deletionLogId} 유무가 용도를 가른다: 있으면 복구, 없으면 신규 가입.
     */
    @Transactional
    public TokenResponse signup(SignupRequest request) {
        JwtTokenProvider.SignupClaims claims = parseSignupToken(request.signupToken());
        Map<ConsentType, ConsentDecision> consents = validateConsents(request);

        if (claims.deletionLogId() != null) {
            RestoreResult result = userService.restore(
                    claims.providerId(), claims.deletionLogId(), consents);
            if (result instanceof RestoreResult.Restored restored) {
                return tokenService.issueTokenPair(restored.userId());
            }
            // Expired — 유예 만료로 식별정보가 파기됨. 같은 트랜잭션에서 신규 가입으로 합류한다.
        }

        User user = createUser(claims);
        // 마이크로초 절삭 — timestamptz(6) 반올림과의 정합(탈퇴·복구 경로와 동일).
        // 동의 상태 조회의 updatedAt이 DB 왕복 후에도 기록 시각과 정확히 일치해야 한다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        consents.forEach((type, decision) -> userConsentRepository.save(
                UserConsent.create(user.getId(), type, decision.agreed(), decision.version(), now)));

        return tokenService.issueTokenPair(user.getId());
    }

    /** 토큰 재발급 — 검증·회전·재사용 감지는 {@link TokenService#reissue}가 담당한다. */
    public TokenResponse reissue(String refreshToken) {
        return tokenService.reissue(refreshToken);
    }

    /** 로그아웃 — AT 유저 소유의 RT만 폐기한다. */
    public void logout(Long userId, String refreshToken) {
        tokenService.logout(userId, refreshToken);
    }

    private JwtTokenProvider.SignupClaims parseSignupToken(String signupToken) {
        if (signupToken == null || signupToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
        }
        try {
            return jwtTokenProvider.parseSignupToken(signupToken);
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.INVALID_SIGNUP_TOKEN);
        }
    }

    /**
     * 동의 제출 검증 — PRD 고정 순서: 형태(400 C002) → 동의서 버전 대조(409 U005) → 필수 동의(400 A004).
     *
     * <p>형태: null 원소·null type·null agreed(필드 누락)·동일 type 중복(동의 증적 입력에서
     * last-wins 수렴 금지)·agreed:true인데 version 누락. 모두 무시하지 않고 400으로 거부한다
     * (잘못된 클라이언트 입력이 NPE→500으로 분류되는 것 방지 포함).
     *
     * <p>버전: agreed:true 항목의 version이 현재 설정 버전과 다르면 409 — "기록 버전 = 사용자가
     * 확인한 버전" 불변식(consent.md 기능 1). agreed:false의 version은 검증·기록에 사용하지 않는다
     * (철회·미동의는 문서 확인을 전제하지 않음).
     */
    private Map<ConsentType, ConsentDecision> validateConsents(SignupRequest request) {
        Map<ConsentType, SignupRequest.ConsentItem> byType = new LinkedHashMap<>();
        if (request.consents() != null) {
            for (SignupRequest.ConsentItem item : request.consents()) {
                if (item == null || item.type() == null || item.agreed() == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (item.agreed() && item.version() == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
                }
                if (byType.put(item.type(), item) != null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
        }
        for (SignupRequest.ConsentItem item : byType.values()) {
            if (item.agreed() && item.version() != consentPolicyProperties.versionOf(item.type())) {
                throw new BusinessException(ErrorCode.CONSENT_VERSION_MISMATCH);
            }
        }
        for (ConsentType type : ConsentType.values()) {
            SignupRequest.ConsentItem item = byType.get(type);
            if (type.isRequired() && (item == null || !item.agreed())) {
                throw new BusinessException(ErrorCode.MISSING_REQUIRED_CONSENT);
            }
        }
        // 기록 결정: AGREED는 제출 버전(대조 통과로 현재 설정 버전과 동일), 명시적 미동의는 현재 설정 버전.
        // LinkedHashMap 유지 — 제출 순서대로 insert되어 id 순서가 결정적이다.
        Map<ConsentType, ConsentDecision> decisions = new LinkedHashMap<>();
        byType.forEach((type, item) -> decisions.put(type, new ConsentDecision(item.agreed(),
                item.agreed() ? item.version() : consentPolicyProperties.versionOf(type))));
        return decisions;
    }

    /**
     * 계정 생성. 선조회로 중복을 걸러내되, 동시 가입 경쟁은 provider_id UNIQUE 제약 위반을
     * 잡아 같은 409로 변환한다(예외 발생 시 트랜잭션 전체 롤백 — 동의 기록도 남지 않는다).
     *
     * <p>토큰 용도 분리(PRD 기능 4): 선조회 결과가 **탈퇴 상태** 계정이면 409가 아니라
     * 401을 던진다 — 신규 가입용 토큰으로는 복구할 수 없으며, 재로그인으로
     * 복구용 토큰(deletionLogId 바인딩)을 받아야 한다.
     */
    private User createUser(JwtTokenProvider.SignupClaims claims) {
        userRepository.findByProviderId(claims.providerId()).ifPresent(existing -> {
            throw new BusinessException(existing.isDeleted()
                    ? ErrorCode.INVALID_SIGNUP_TOKEN
                    : ErrorCode.ALREADY_REGISTERED);
        });
        try {
            return userRepository.saveAndFlush(User.create(claims.providerId(), claims.email(), claims.nickname()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
    }
}
