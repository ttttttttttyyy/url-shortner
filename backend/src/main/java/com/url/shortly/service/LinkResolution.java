package com.url.shortly.service;

import com.url.shortly.Entities.Link;

/**
 * Outcome of resolving a public short code (SoW FR-03).
 *
 * <p>The service, not the controller, decides whether a link may redirect.
 * This keeps the precedence rules in one place and leaves room for FR-13
 * (password form) and FR-14 (ad interstitial) without collapsing every
 * non-active state into a single HTTP status.
 *
 * <p>{@link Outcome#ACTIVE} is the only outcome that may answer with a
 * redirect. The compact constructor makes invalid combinations unrepresentable:
 * only {@link Outcome#NOT_FOUND} carries no link, and every other outcome
 * carries the row that produced it.
 */
public record LinkResolution(Link link, Outcome outcome) {

    public enum Outcome {
        /** The link may redirect to its destination. */
        ACTIVE,
        /** {@code expiresAt} has passed. */
        EXPIRED,
        /** The row is soft-deleted ({@code deletedAt} and/or status). */
        DELETED,
        /** An owner or administrator disabled the link. */
        DISABLED,
        /** Moderation blocked the link (FR-16). */
        BLOCKED,
        /** FR-13 seam: the visitor must prove knowledge of the password. */
        PASSWORD_REQUIRED,
        /** No row exists for the requested code. */
        NOT_FOUND
    }

    public LinkResolution {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (outcome == Outcome.NOT_FOUND ? link != null : link == null) {
            throw new IllegalArgumentException("link presence must match outcome: " + outcome);
        }
    }

    public static LinkResolution notFound() {
        return new LinkResolution(null, Outcome.NOT_FOUND);
    }

    /** @return true only for {@link Outcome#ACTIVE} */
    public boolean redirects() {
        return outcome == Outcome.ACTIVE;
    }
}
