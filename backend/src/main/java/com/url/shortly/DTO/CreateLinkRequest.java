package com.url.shortly.DTO;

import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/links}. Fields are placeholders until
 * validation rules are added.
 */
public record CreateLinkRequest(
        String url,
        String alias,
        String password,
        Instant expiresAt) {
}
