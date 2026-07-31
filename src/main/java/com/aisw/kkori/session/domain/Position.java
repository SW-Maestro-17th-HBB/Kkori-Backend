package com.aisw.kkori.session.domain;

/**
 * 면접 직무 — 면접 유형과 독립인 축. 세션에 저장해 두었다가 Agent 디스패치 시 전달되어
 * 질문 개인화 입력으로 쓰인다(후속 스토리). 직무 추가는 enum 값 추가로 확장한다.
 */
public enum Position {

    BACKEND,
    FRONTEND,
}
