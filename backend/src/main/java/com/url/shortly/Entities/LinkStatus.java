package com.url.shortly.Entities;

/**
 * Lifecycle state of a short link (SoW FR-03 / FR-16).
 *
 * <p>{@code EXPIRED} is deliberately not a state here: expiry is derived from
 * {@code expiresAt} so it can never fall out of sync with the timestamp.
 */
public enum LinkStatus {
    ACTIVE,
    DISABLED,
    BLOCKED,
    DELETED
}
