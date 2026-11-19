package com.url.shortly.ratelimit;

/**
 * Result of one token-bucket check.
 *
 * @param allowed whether the request may proceed
 * @param policy the policy that produced this result
 * @param retryAfterSeconds positive only for a rejection
 */
public record RateLimitDecision(boolean allowed, String policy, long retryAfterSeconds) {

    public static RateLimitDecision allowed(String policy) {
        return new RateLimitDecision(true, policy, 0L);
    }

    public static RateLimitDecision rejected(String policy, long retryAfterSeconds) {
        return new RateLimitDecision(false, policy, Math.max(1L, retryAfterSeconds));
    }
}
