package com.aisw.kkori.user.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 수집 동의 항목.
 *
 * <p>필수 항목 3종({@code privacy}·{@code audio_usage}·{@code resume_usage})에 모두
 * 동의해야 가입이 완료된다.
 */
@Getter
@RequiredArgsConstructor
public enum ConsentType {

    PRIVACY("privacy", true),
    AUDIO_USAGE("audio_usage", true),
    RESUME_USAGE("resume_usage", true),
    MARKETING("marketing", false),
    ;

    @JsonValue
    private final String value;
    private final boolean required;

    /**
     * 경로 변수 해석용 — API 응답과 동일한 소문자 스네이크 표기({@code @JsonValue} 문자열)로 찾는다.
     * enum 자동 바인딩은 상수명(MARKETING) 기준인 데다 실패가 공통 500으로 빠지므로 쓰지 않는다
     * (알 수 없는 항목은 도메인 코드 U003이어야 함 — PRD consent.md 기능 4).
     */
    public static Optional<ConsentType> fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst();
    }
}
