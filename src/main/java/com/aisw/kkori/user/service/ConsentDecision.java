package com.aisw.kkori.user.service;

/**
 * 검증을 통과한 동의 제출의 항목별 기록 결정 (PRD {@code consent.md} 기능 1 기록 규칙).
 *
 * <p>{@code AGREED}는 사용자가 확인·제출한 버전으로(대조를 통과했으므로 현재 설정 버전과 같다),
 * 명시적 미동의({@code agreed: false})는 현재 설정 버전으로 기록한다.
 */
public record ConsentDecision(boolean agreed, int version) {
}
