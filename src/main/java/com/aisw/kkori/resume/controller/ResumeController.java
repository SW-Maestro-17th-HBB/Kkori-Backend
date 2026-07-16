package com.aisw.kkori.resume.controller;

import com.aisw.kkori.global.response.ApiResponse;
import com.aisw.kkori.resume.api.ResumeApi;
import com.aisw.kkori.resume.dto.ResumeParsedResponse;
import com.aisw.kkori.resume.dto.ResumeParsedUpdateRequest;
import com.aisw.kkori.resume.dto.ResumeReanalyzeResponse;
import com.aisw.kkori.resume.dto.ResumeUploadResponse;
import com.aisw.kkori.resume.service.ResumeParsedService;
import com.aisw.kkori.resume.service.ResumeUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController implements ResumeApi {

    private final ResumeUploadService resumeUploadService;
    private final ResumeParsedService resumeParsedService;

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

    @GetMapping("/{resumeId}/parsed")
    public ResponseEntity<ApiResponse<ResumeParsedResponse>> getParsed(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long resumeId
    ) {
        return ResponseEntity.ok(ApiResponse.success(resumeParsedService.getParsed(userId, resumeId)));
    }

    @PatchMapping("/{resumeId}/parsed")
    public ResponseEntity<ApiResponse<ResumeParsedResponse>> updateParsed(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long resumeId,
            @Valid @RequestBody ResumeParsedUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                resumeParsedService.updateParsed(userId, resumeId, request.structuredData())));
    }

    @PostMapping("/{resumeId}/reanalyze")
    public ResponseEntity<ApiResponse<ResumeReanalyzeResponse>> reanalyze(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long resumeId
    ) {
        return ResponseEntity.ok(ApiResponse.success(resumeParsedService.reanalyze(userId, resumeId)));
    }
}
