package com.url.shortly.ratelimit;

/**
 * Endpoints that share a rate-limit policy family.
 *
 * <p>Only link creation exists today; authentication attempts (FR-05) will add
 * their own scope rather than reusing the creation buckets.
 */
public enum RateLimitScope {
    LINK_CREATION
}
