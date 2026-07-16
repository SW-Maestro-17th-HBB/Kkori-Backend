package com.aisw.kkori.user.dto;

import com.aisw.kkori.user.domain.ConsentAction;
import com.aisw.kkori.user.domain.ConsentType;
import com.aisw.kkori.user.domain.UserConsent;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 동의 상태 응답 (PRD consent.md 기능 3) — 유형별 최신 행 기준의 현재 상태.
 * 동의 상태 조회와 선택 동의 변경이 같은 형식을 반환한다.
 */
public record UserConsentsResponse(List<ConsentStateItem> consents) {

    /** 이력 없는 항목은 미동의 — {@code agreed=false}, {@code version}·{@code updatedAt}은 null. */
    public record ConsentStateItem(ConsentType type, boolean agreed, Integer version, Instant updatedAt) {
    }

    /** 전 항목을 enum 순서로 항상 포함한다 — 이력 유무와 무관하게 현재 상태를 표현하기 위함. */
    public static UserConsentsResponse from(Map<ConsentType, UserConsent> latestByType) {
        return new UserConsentsResponse(Arrays.stream(ConsentType.values())
                .map(type -> {
                    UserConsent latest = latestByType.get(type);
                    if (latest == null) {
                        return new ConsentStateItem(type, false, null, null);
                    }
                    return new ConsentStateItem(type, latest.getAction() == ConsentAction.AGREED,
                            latest.getVersion(), latest.getCreatedAt());
                })
                .toList());
    }
}
