package com.aisw.kkori.session.dto;

/**
 * 에이전트 종료 표식 (크로스 레포 계약 — Kkori-AI interview-end.md §3).
 *
 * <p>에이전트가 CLOSING 진입 부수효과로 Redis에 남기는 종료 증거다. Spring은 표식의
 * <b>존재</b>만 판별 신호로 쓰고 {@code cause} 값으로 분기하지 않는다(진단 로그 전용) —
 * 에이전트가 cause를 추가해도 Spring 계약이 불변이도록 하는 방침. 값 파싱에 실패한
 * 표식도 "존재"로 취급한다.
 */
public record TerminationMarker(String cause, String markedAt) {

    /** 값이 계약 JSON으로 파싱되지 않는 표식 — 존재 자체는 유효한 신호다. */
    public static TerminationMarker unparseable() {
        return new TerminationMarker("<unparseable>", null);
    }
}
