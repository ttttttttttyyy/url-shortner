package com.url.shortly.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * One token bucket policy: at most {@code limit} requests per {@code window}.
 *
 * <p>{@code global} means the bucket is shared by every caller (used for the
 * per-instance safety valve). Non-global buckets are keyed by client identity.
 */
public record RateLimitPolicy(String name, int limit, Duration window, boolean global) {

    public RateLimitPolicy {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(window, "window");
        if (name.isBlank()) {
            throw new IllegalArgumentException("policy name is required");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + name);
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive: " + name);
        }
    }
}
