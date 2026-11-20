package com.url.shortly.enums;

import java.time.Instant;

import com.url.shortly.Entities.LinkStatus;

/**
 * The status a link owner sees in the dashboard.
 *
 * <p>Expiry is derived from {@code expiresAt} rather than stored as a row
 * status (FR-03), so a link whose stored status is {@code ACTIVE} can still be
 * expired. {@code ACTIVE} in this enum means "ACTIVE and not expired"; the
 * stored value remains available as {@code LinkListItem.status} for clients
 * that need it.
 */
public enum LinkDisplayStatus {
    ACTIVE,
    EXPIRED,
    DISABLED,
    BLOCKED,
    DELETED;

    public static LinkDisplayStatus of(LinkStatus status, Instant expiresAt, Instant now) {
        if (status == LinkStatus.ACTIVE
                && expiresAt != null && !expiresAt.isAfter(now)) {
            return EXPIRED;
        }
        return switch (status) {
            case ACTIVE -> ACTIVE;
            case DISABLED -> DISABLED;
            case BLOCKED -> BLOCKED;
            case DELETED -> DELETED;
        };
    }
}
