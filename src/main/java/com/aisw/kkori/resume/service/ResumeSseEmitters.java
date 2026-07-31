package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.sse.UserSseEmitters;
import org.springframework.stereotype.Component;

/** 이력서 채널({@code /sse/v1/resumes})의 SSE 연결 레지스트리 — 동작은 전부 공용 부품에 있다. */
@Component
public class ResumeSseEmitters extends UserSseEmitters {
}
