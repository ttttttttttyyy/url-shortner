package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.enums.LinkDisplayStatus;

/**
 * One row of the authenticated dashboard.
 *
 * <p>{@code status} is the stored row status, while {@code effectiveStatus}
 * also accounts for expiry, which is derived from {@code expiresAt} (FR-03).
 * This keeps the table consistent with the summary's {@code activeLinks} and
 * {@code expiredLinks} counts. Password hashes are never projected, and
 * {@code shortUrl} comes from the configured base URL, not the request's Host
 * header.
 */
public record LinkListItem(
        Long id,
        String shortCode,
        String shortUrl,
        String destinationUrl,
        LinkStatus status,
        LinkDisplayStatus effectiveStatus,
        boolean customAlias,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static LinkListItem from(Link link, String shortUrl, Instant now) {
        return new LinkListItem(
                link.getId(),
                link.getShortCode(),
                shortUrl,
                link.getUrl(),
                link.getStatus(),
                LinkDisplayStatus.of(link.getStatus(), link.getExpiresAt(), now),
                link.isCustomAlias(),
                link.getExpiresAt(),
                link.getCreatedAt(),
                link.getUpdatedAt());
    }
}
