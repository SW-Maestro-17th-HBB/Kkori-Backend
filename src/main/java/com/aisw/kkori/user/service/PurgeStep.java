package com.aisw.kkori.user.service;

import com.aisw.kkori.user.domain.PurgeDetail;

/**
 * 파기 배치의 한 단계 (PRD deletion.md 기능 3 — 이력서·세션·리포트·RT·unlink).
 *
 * <p>계약: (1) <b>멱등</b> — 재시도·재선점·중복 실행에서 이미 파기된 대상은 건너뛰고 예외를 내지 않는다.
 * (2) DB 쓰기는 자체 트랜잭션에서 <b>user 행 잠금(무필터)을 선행</b>하고, S3·카카오 같은 외부 호출은
 * 트랜잭션·잠금 밖에서 DB 포인터 삭제보다 <b>먼저</b> 수행한다. (3) 실패는 {@link RuntimeException}으로
 * 던진다 — 오케스트레이터가 건을 {@code FAILED}로 전환해 다음 회차에 재시도한다. (4) 반환 기록에
 * 개인정보를 담지 않는다(건수·상태만).
 *
 * <p>구현체는 계정 도메인이 조립 순서({@link #order()})대로 실행한다. 종결(식별정보 마스킹·PURGED)은
 * 단계가 아니라 {@link DeletionPurgeService}가 직접 수행한다.
 */
public interface PurgeStep {

    int ORDER_RESUMES = 10;
    int ORDER_SESSIONS = 20;
    int ORDER_REPORTS = 30;
    int ORDER_REFRESH_TOKENS = 40;
    int ORDER_UNLINK = 50;

    /** {@link PurgeDetail}의 단계 키. */
    String key();

    /** 실행 순서 — 작을수록 먼저. */
    int order();

    PurgeDetail.StepResult execute(PurgeTarget target);
}
