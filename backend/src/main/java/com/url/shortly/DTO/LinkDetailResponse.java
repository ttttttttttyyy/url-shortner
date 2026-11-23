package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.enums.LinkDisplayStatus;

/**
 * Detail projection for {@code GET/PATCH /api/v1/links/{linkId}}.
 *
 * <p>The password hash is never returned; only a boolean indicates whether a
 * link is password protected. As in the dashboard list, {@code status} is the
 * stored status while {@code effectiveStatus} also accounts for expiry.
 */
public record LinkDetailResponse(
        Long id,
        String shortCode,
        String shortUrl,
        String destinationUrl,
        LinkStatus status,
        LinkDisplayStatus effectiveStatus,
        boolean customAlias,
        boolean passwordProtected,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static LinkDetailResponse from(Link link, String shortUrl, Instant now) {
        return new LinkDetailResponse(
                link.getId(),
                link.getShortCode(),
                shortUrl,
                link.getUrl(),
                link.getStatus(),
                LinkDisplayStatus.of(link.getStatus(), link.getExpiresAt(), now),
                link.isCustomAlias(),
                link.getPasswordHash() != null,
                link.getExpiresAt(),
                link.getCreatedAt(),
                link.getUpdatedAt());
    }
}
