package com.url.shortly.ratelimit;

/**
 * Check-and-consume seam so FR-19 can replace the in-memory implementation
 * with a Redis-backed one without changing the interceptor or its contract.
 */
public interface RateLimiter {

    /**
     * Atomically refill and consume one token for {@code key} under
     * {@code policy}.
     *
     * @param key client identity (or a global marker), never a raw IP
     * @param policy bucket capacity and refill window
     */
    RateLimitDecision tryAcquire(String key, RateLimitPolicy policy);
}
