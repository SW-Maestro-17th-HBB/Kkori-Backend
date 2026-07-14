package com.aisw.kkori.resume.service;

import com.aisw.kkori.global.exception.BusinessException;
import com.aisw.kkori.global.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;

/**
 * 업로드 PDF 검증 (docs/requirements/resume/resume.md §1 검증 순서).
 *
 * <p>확장자·Content-Type은 클라이언트가 조작할 수 있으므로,
 * 최종 검증은 PDFBox로 실제 파일을 열어보는 것으로 한다 (페이지 수 확인 겸용).
 */
@Component
public class PdfValidator {

    static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;
    static final int MAX_PAGE_COUNT = 10;
    static final String PDF_MIME_TYPE = "application/pdf";

    /**
     * 파일 존재 → 확장자·MIME → 크기 → PDF 열기·페이지 수 순으로 검증한다.
     *
     * @return PDF 페이지 수
     */
    public int validate(MultipartFile file) {
        validateFilePresence(file);
        validateFileType(file);
        validateFileSize(file);
        return validatePdfContent(file);
    }

    /** 파일 존재 여부 (R001). */
    private void validateFilePresence(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
    }

    /** 확장자·Content-Type 검사 (R002) — 클라이언트 신고값이라 1차 선별용. */
    private void validateFileType(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        boolean hasPdfExtension = StringUtils.hasText(fileName)
                && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (!hasPdfExtension || !PDF_MIME_TYPE.equals(file.getContentType())) {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }
    }

    /** 크기 검사 (R003) — MockMvc 등 서블릿 컨테이너 한도를 거치지 않는 경로를 위해 서비스 레벨에서도 검증. */
    private void validateFileSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    /**
     * 실제 파일을 열어 유효성·페이지 수를 검사한다 (R004·R005) — 조작 불가능한 최종 검증.
     *
     * @return PDF 페이지 수
     */
    private int validatePdfContent(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PAGE_COUNT) {
                throw new BusinessException(ErrorCode.PAGE_LIMIT_EXCEEDED);
            }
            return pageCount;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_PDF);
        }
    }
}
