package com.url.shortly.service;

import java.net.URI;
import java.util.Locale;

/**
 * Reduces a referrer header to a privacy-safe, stable host value.
 *
 * <p>Full URLs can carry tokens or other secrets in the query string;
 * {@code www.} is stripped so the same site does not fragment the breakdown.
 * Used at both write time (FR-09) and read time (FR-10) so legacy rows written
 * before host-only storage are cleaned too.
 */
public final class ReferrerNormalizer {

    private ReferrerNormalizer() {
    }

    public static String host(String rawReferrer) {
        if (rawReferrer == null || rawReferrer.isBlank()) {
            return null;
        }
        String trimmed = rawReferrer.trim();
        try {
            String host = URI.create(trimmed).getHost();
            if (host == null || host.isBlank()) {
                // Legacy FR-09 rows may already hold a bare host (no scheme),
                // which URI does not parse as an authority.
                if (!trimmed.matches("[A-Za-z0-9.-]+")) {
                    return null;
                }
                host = trimmed;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
