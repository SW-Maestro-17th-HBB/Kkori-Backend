package com.aisw.kkori.resume.service;

import com.aisw.kkori.resume.dto.ResumeParseRequestedMessage;

/**
 * 이력서 분석 요청 전달의 추상화 — 동기/비동기 부하 테스트 비교를 위해 전달 방식을
 * 설정({@code app.ai-dispatch.mode})으로 갈아끼운다 (HBB1-327).
 *
 * <p>메서드가 시점별로 둘인 이유: 두 모드가 실행돼야 하는 시점이 다르기 때문이다.
 * 비동기 발행은 트랜잭션 안에서 해야 실패 시 상태 변경까지 함께 롤백되고(기존 계약),
 * 동기 HTTP 호출은 트랜잭션 밖에서 해야 워커 처리를 기다리는 동안 DB 커넥션·잠금을
 * 쥐지 않는다. 호출자는 두 메서드를 <b>모두</b> 정해진 위치에서 호출하고, 구현체는
 * 자기 시점의 메서드만 실제로 동작한다.
 *
 * <pre>
 * transactionTemplate.execute(tx -&gt; {
 *     ...상태 변경...
 *     requester.dispatchInTransaction(message);   // 비동기: 스트림 발행 / 동기: no-op
 *     ...
 * });
 * requester.dispatchAfterCommit(message);         // 비동기: no-op / 동기: 워커 HTTP 호출(블로킹)
 * </pre>
 */
public interface ResumeAnalysisRequester {

    /**
     * 호출자의 트랜잭션 안에서 호출한다.
     * 비동기: 스트림 발행 — 실패 시 {@code RESUME_ANALYSIS_REQUEST_FAILED}로 던져 트랜잭션을 롤백시킨다.
     * 동기: 아무것도 하지 않는다.
     */
    void dispatchInTransaction(ResumeParseRequestedMessage message);

    /**
     * 호출자의 트랜잭션이 커밋된 뒤에 호출한다.
     * 비동기: 아무것도 하지 않는다.
     * 동기: 워커 HTTP를 호출하고 분석 완료까지 블로킹 — 실패 시 상태를 FAILED로 전이시키고
     * {@code RESUME_ANALYSIS_REQUEST_FAILED}를 던진다(복구는 기존 재분석 경로).
     */
    void dispatchAfterCommit(ResumeParseRequestedMessage message);
}
