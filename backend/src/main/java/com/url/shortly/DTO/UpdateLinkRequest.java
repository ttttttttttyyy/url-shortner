package com.url.shortly.DTO;

import java.time.Instant;

/**
 * Request body for {@code PATCH /api/v1/links/{linkId}}. Null fields mean
 * "leave unchanged". Because {@code null} cannot distinguish "unchanged" from
 * "clear the expiry", {@code clearExpiry = true} is the explicit way to remove
 * an expiration; it is mutually exclusive with {@code expiresAt}.
 */
public record UpdateLinkRequest(
        String url,
        String alias,
        String password,
        Instant expiresAt,
        Boolean active,
        Boolean clearExpiry) {
}
