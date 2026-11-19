package com.url.shortly.service.exeptions;

/**
 * Thrown when a rate-limit policy rejects a request.
 *
 * <p>Carries the policy name for diagnostics and the largest observed
 * {@code Retry-After}, so multiple simultaneously failing policies produce a
 * deterministic response.
 */
public class RateLimitExceededException extends RuntimeException {

    private final String policy;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String policy, long retryAfterSeconds) {
        super("Too many requests. Try again later.");
        this.policy = policy;
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public String policy() {
        return policy;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
