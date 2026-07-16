package com.aisw.kkori.user.dto;

import java.time.Instant;

/** 회원 탈퇴 응답. {@code purgeScheduledAt}은 개인정보가 파기되는 예정 시각(탈퇴 시각 + 유예 기간). */
public record WithdrawResponse(Instant purgeScheduledAt) {
}
