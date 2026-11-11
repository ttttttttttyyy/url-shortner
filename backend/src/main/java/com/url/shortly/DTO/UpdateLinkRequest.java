package com.url.shortly.DTO;

import java.time.Instant;

/**
 * Request body for {@code PATCH /api/v1/links/{linkId}}. Null fields mean
 * "leave unchanged".
 */
public record UpdateLinkRequest(
        String url,
        String alias,
        String password,
        Instant expiresAt,
        Boolean active) {
}
