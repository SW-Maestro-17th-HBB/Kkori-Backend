package com.aisw.kkori.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 테스트용 PDF 생성 유틸. */
public final class ResumePdfFixtures {

    private ResumePdfFixtures() {
    }

    public static byte[] pdfWithPages(int pages) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("테스트 PDF 생성 실패", e);
        }
    }

    public static byte[] corruptedPdf() {
        return "this is not a pdf".getBytes();
    }

    /** 크기 검증용 — 유효한 PDF일 필요 없음 (크기 검증이 파싱보다 먼저). */
    public static byte[] oversizedBytes() {
        return new byte[11 * 1024 * 1024];
    }
}
