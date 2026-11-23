package com.url.shortly.DTO;

import java.time.Instant;

/**
 * Request body for {@code PATCH /api/v1/links/{linkId}}. Null fields mean
 * "leave unchanged". Because {@code null} cannot distinguish "unchanged" from
 * "clear", {@code clearExpiry} / {@code clearPassword} are the explicit ways
 * to remove an expiration or a password; each is mutually exclusive with its
 * corresponding value field.
 */
public record UpdateLinkRequest(
        String url,
        String alias,
        String password,
        Instant expiresAt,
        Boolean active,
        Boolean clearExpiry,
        Boolean clearPassword) {
}
