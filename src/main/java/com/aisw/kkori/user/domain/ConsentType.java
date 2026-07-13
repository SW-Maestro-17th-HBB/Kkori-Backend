package com.aisw.kkori.user.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
}
