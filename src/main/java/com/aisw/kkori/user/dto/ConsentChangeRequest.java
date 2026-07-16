package com.aisw.kkori.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 선택 동의 변경 요청 (PRD consent.md 기능 4).
 *
 * <p>{@code agreed}는 bean validation 필수(누락·null 시 공통 400 C002 — PRD 명시).
 * {@code version}은 사용자가 확인한 동의서 버전으로 {@code agreed=true}일 때 필수이며
 * 서비스에서 검증한다(누락 400, 현재 버전 불일치 409 U005). 철회({@code agreed=false})는
 * 문서 확인을 전제하지 않으므로 version을 받지 않는다 — 전달돼도 검증·기록에 사용하지 않는다.
 */
public record ConsentChangeRequest(@NotNull Boolean agreed, Integer version) {
}
