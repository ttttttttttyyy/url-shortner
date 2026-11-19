package com.url.shortly.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way hashing for refresh tokens and rate-limit account keys.
 *
 * <p>SHA-256 is sufficient here because the input is either a high-entropy
 * random token or an email used only as an in-memory cache key; neither is a
 * user-chosen password.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
