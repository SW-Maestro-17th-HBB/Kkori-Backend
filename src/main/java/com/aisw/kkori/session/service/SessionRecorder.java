package com.aisw.kkori.session.service;

/**
 * 세션 룸 오디오 녹음 시작 추상 (PRD interview-recording.md 기능 1). 실제 시작은 벤더 어댑터
 * ({@code global.livekit.LiveKitEgressRecorder})가 담당한다 — 룸·토큰·디스패치와 동일한 격리 구조.
 */
public interface SessionRecorder {

    /**
     * 룸의 오디오 녹음을 시작하고 녹음 식별자(egress id)를 반환한다.
     *
     * @throws RuntimeException 시작 실패 시 — 녹음은 부가 기능이라 호출측이 warn 후 진행한다.
     *         {@code ErrorCode} 매핑을 두지 않는다: 이 실패는 API 응답으로 표면화되지 않는다
     *         (음성 분석 누락은 워커 계약의 유예 완성 경로가 흡수).
     */
    String startRecording(String roomName);
}
