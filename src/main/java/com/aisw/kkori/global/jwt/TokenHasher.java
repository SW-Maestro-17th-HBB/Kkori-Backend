package com.aisw.kkori.global.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Refresh Token 평문의 SHA-256 해시 유틸.
 *
 * RT는 평문 대신 이 해시로 DB에 저장·조회한다(유출 대비). 해시는 단방향이지만
 * 결정적이므로, 클라이언트가 보낸 평문을 다시 해시해 저장값과 대조하는 방식으로 조회한다.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    /** SHA-256 해시를 64자 소문자 hex 문자열로 반환한다. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
