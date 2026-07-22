package com.aisw.kkori.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 목록 API 공통 페이지 엔벨로프 (docs/requirements/resume/resume.md §2).
 *
 * <p>totalPages는 totalElements와 size로 유도 가능하므로 내려주지 않는다.
 * 다른 도메인의 목록 API도 이 엔벨로프를 재사용한다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext()
        );
    }
}
