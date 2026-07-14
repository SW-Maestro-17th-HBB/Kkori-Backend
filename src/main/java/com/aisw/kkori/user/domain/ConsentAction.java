package com.aisw.kkori.user.domain;

/** 동의 이력의 행위 구분. append-only로 기록되며 최신 행이 현재 상태다. */
public enum ConsentAction {
    AGREED,
    WITHDRAWN,
}
