package com.url.shortly.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * In-memory token bucket.
 *
 * <p>State is per instance and resets on restart; that is an accepted MVP
 * limitation. Move to a shared store (Redis) when running more than one node
 * or when FR-19 introduces plan quotas.
 *
 * <p>The Caffeine cache bounds memory and expires idle buckets, while
 * {@code asMap().compute} makes refill-and-consume one atomic operation.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private static final long MAX_BUCKETS = 100_000L;
    private static final Duration IDLE_EXPIRY = Duration.ofHours(48);

    private final Cache<String, Bucket> buckets;
    private final Clock clock;

    public TokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(IDLE_EXPIRY)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    @Override
    public RateLimitDecision tryAcquire(String key, RateLimitPolicy policy) {
        String cacheKey = policy.name() + '|' + key;
        long now = clock.millis();
        RateLimitDecision[] result = new RateLimitDecision[1];

        buckets.asMap().compute(cacheKey, (ignored, previous) -> {
            double capacity = policy.limit();
            long windowMillis = policy.window().toMillis();
            double tokens = previous == null ? capacity : previous.tokens();
            long lastRefill = previous == null ? now : previous.lastRefillMillis();
            long elapsed = Math.max(0L, now - lastRefill);

            tokens = Math.min(capacity, tokens + (double) elapsed * capacity / windowMillis);

            if (tokens >= 1.0d) {
                result[0] = RateLimitDecision.allowed(policy.name());
                return new Bucket(tokens - 1.0d, now);
            }

            long waitMillis = (long) Math.ceil((1.0d - tokens) * windowMillis / capacity);
            long retryAfterSeconds = Math.max(1L, (waitMillis + 999L) / 1000L);
            result[0] = RateLimitDecision.rejected(policy.name(), retryAfterSeconds);
            return new Bucket(tokens, now);
        });

        return result[0];
    }

    private record Bucket(double tokens, long lastRefillMillis) {
    }
}
