package com.aisw.kkori.session.service;

/**
 * candidate 참가자 신원의 파생 규칙 — {@code candidate-{sessionId}} (HBB1-18 확정, HBB1-308 계약 인용).
 *
 * <p>결정적 파생(난수·시각 성분 없음)이라 재입장 토큰도 최초와 같은 identity가 자동으로
 * 성립한다 — 에이전트의 재개 판정(identity 일치)이 이 보장 위에 있다. 생성·재입장 발급과
 * 룸 대조(candidate 실존 판별)가 모두 이 규칙 하나를 공유한다.
 */
public final class CandidateIdentity {

    private static final String PREFIX = "candidate-";

    private CandidateIdentity() {
    }

    public static String of(long sessionId) {
        return PREFIX + sessionId;
    }
}
