package com.aisw.kkori.auth.service;

import com.aisw.kkori.auth.dto.KakaoLoginResponse;
import com.aisw.kkori.auth.dto.SignupRequest;
import com.aisw.kkori.auth.dto.TokenResponse;
import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import com.aisw.kkori.global.jwt.JwtTokenProvider;
import com.aisw.kkori.global.oauth.KakaoOAuthClient;
import com.aisw.kkori.global.oauth.KakaoUserInfo;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.User;
import com.aisw.kkori.user.domain.UserConsent;
import com.aisw.kkori.user.repository.UserConsentRepository;
import com.aisw.kkori.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 동의서 버전 — 버전 관리 정책은 HBB1-12 범위로, 그 전까지 1로 고정. */
    private static final int CONSENT_VERSION = 1;

    private final KakaoOAuthClient kakaoOAuthClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;

    /** 카카오 인가 코드를 교환·조회한 뒤 신규/기존/복구를 판정해 응답한다. */
    public KakaoLoginResponse kakaoLogin(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_CODE);
        }
        KakaoUserInfo info = kakaoOAuthClient.authenticate(code);
        return tokenService.processKakaoLogin(info);
    }

    /**
     * 회원가입 완료 — signup token 검증, 필수 동의 확인 후 계정 생성과 동의 기록을
     * 한 트랜잭션으로 처리하고 JWT를 발급한다.
     */
    @Transactional
    public TokenResponse signup(SignupRequest request) {
        JwtTokenProvider.SignupClaims claims = parseSignupToken(request.signupToken());
        Map<ConsentType, Boolean> consents = validateConsents(request);

        User user = createUser(claims);
        consents.forEach((type, agreed) ->
                userConsentRepository.save(UserConsent.record(user.getId(), type, agreed, CONSENT_VERSION)));

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
     * 필수 항목(privacy·audio_usage·resume_usage)이 모두 동의됐는지 확인한다.
     * null 원소·null type은 의미 없는 입력이므로 무시하지 않고 400으로 거부한다
     * (잘못된 클라이언트 입력이 NPE→500으로 분류되는 것 방지).
     */
    private Map<ConsentType, Boolean> validateConsents(SignupRequest request) {
        Map<ConsentType, Boolean> byType = new LinkedHashMap<>();
        if (request.consents() != null) {
            for (SignupRequest.ConsentItem item : request.consents()) {
                if (item == null || item.type() == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
                }
                byType.put(item.type(), item.agreed());
            }
        }
        for (ConsentType type : ConsentType.values()) {
            if (type.isRequired() && !byType.getOrDefault(type, false)) {
                throw new BusinessException(ErrorCode.MISSING_REQUIRED_CONSENT);
            }
        }
        return byType;
    }

    /**
     * 계정 생성. 선조회로 중복을 걸러내되, 동시 가입 경쟁은 provider_id UNIQUE 제약 위반을
     * 잡아 같은 409로 변환한다(예외 발생 시 트랜잭션 전체 롤백 — 동의 기록도 남지 않는다).
     */
    private User createUser(JwtTokenProvider.SignupClaims claims) {
        if (userRepository.findByProviderId(claims.providerId()).isPresent()) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
        try {
            return userRepository.saveAndFlush(User.create(claims.providerId(), claims.email(), claims.nickname()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED);
        }
    }
}
