package com.url.shortly.security;

import java.util.Locale;

/**
 * Canonical email form used for storage, lookup and rate-limit keys.
 *
 * <p>Lowercasing the whole address is slightly stricter than RFC 5321 (which
 * treats the local part as case-sensitive). In practice every mainstream mail
 * provider treats addresses case-insensitively, and a single canonical form
 * prevents duplicate accounts.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
