package com.aisw.kkori.report.service;

import com.aisw.kkori.global.sse.UserSseEmitters;
import org.springframework.stereotype.Component;

/** 리포트 채널({@code /sse/v1/reports})의 SSE 연결 레지스트리 — 동작은 전부 공용 부품에 있다. */
@Component
public class ReportSseEmitters extends UserSseEmitters {
}
