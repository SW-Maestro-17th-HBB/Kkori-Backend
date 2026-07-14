package com.aisw.kkori.resume.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.resume.api.ResumeApi;
import com.aisw.kkori.resume.dto.ResumeUploadResponse;
import com.aisw.kkori.resume.service.ResumeUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController implements ResumeApi {

    private final ResumeUploadService resumeUploadService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ResumeUploadResponse>> upload(
            // JwtAuthenticationFilter가 principal에 userId(Long)를 심는다 (SecurityConfig에서 인증 강제)
            @AuthenticationPrincipal Long userId,
            // required = false: 파일 누락을 Spring 기본 400 대신 FILE_REQUIRED(R001) 엔벨로프로 응답하기 위함
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) {
        ResumeUploadResponse response = resumeUploadService.upload(userId, file, title);
        // 새로 생성됐으면 201, 중복이라 기존 정보를 반환했으면 200 (아무것도 생성되지 않음)
        HttpStatus status = response.duplicated() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.success(response));
    }
}
