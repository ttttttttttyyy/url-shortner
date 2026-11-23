package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.Entities.Link;

/**
 * Response body for {@code POST /api/v1/links}.
 *
 * <p>{@code shortUrl} is derived from the configured {@code app.base-url},
 * never from the request's Host header.
 */
public record CreateLinkResponse(
        Long id,
        String shortCode,
        String shortUrl,
        String destinationUrl,
        boolean customAlias,
        Instant expiresAt,
        Instant createdAt) {

    public static CreateLinkResponse from(Link link, String shortUrl) {
        return new CreateLinkResponse(
                link.getId(),
                link.getShortCode(),
                shortUrl,
                link.getUrl(),
                link.isCustomAlias(),
                link.getExpiresAt(),
                link.getCreatedAt());
    }
}
