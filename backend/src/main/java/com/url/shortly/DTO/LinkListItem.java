package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;

/**
 * One row of the authenticated dashboard.
 *
 * <p>Password hashes are never projected. {@code shortUrl} comes from the
 * configured base URL, not the request's Host header.
 */
public record LinkListItem(
        Long id,
        String shortCode,
        String shortUrl,
        String destinationUrl,
        LinkStatus status,
        boolean customAlias,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static LinkListItem from(Link link, String shortUrl) {
        return new LinkListItem(
                link.getId(),
                link.getShortCode(),
                shortUrl,
                link.getUrl(),
                link.getStatus(),
                link.isCustomAlias(),
                link.getExpiresAt(),
                link.getCreatedAt(),
                link.getUpdatedAt());
    }
}
