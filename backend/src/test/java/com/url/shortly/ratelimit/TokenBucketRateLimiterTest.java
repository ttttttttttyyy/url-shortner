package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-04: token-bucket contract.
 *
 * <p>The clock is injected so refill behaviour can be asserted without
 * sleeping. The concurrency test pins the atomic check-and-consume guarantee;
 * a naive read/check/write implementation would over-admit under load.
 */
class TokenBucketRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-19T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);

    @Test
    @DisplayName("allows exactly the burst limit, then rejects with a retry hint")
    void allowsLimitThenRejects() {
        RateLimitPolicy policy = new RateLimitPolicy("burst", 3, Duration.ofSeconds(60), false);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("client", policy).allowed()).isTrue();
        }

        RateLimitDecision rejected = limiter.tryAcquire("client", policy);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.policy()).isEqualTo("burst");
        assertThat(rejected.retryAfterSeconds()).isEqualTo(20);
    }

    @Test
    @DisplayName("refills one token per window/limit interval")
    void refillsOverTime() {
        RateLimitPolicy policy = new RateLimitPolicy("burst", 3, Duration.ofSeconds(60), false);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client", policy);
        }
        assertThat(limiter.tryAcquire("client", policy).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("client", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("client", policy).allowed()).isFalse();
    }

    @Test
    @DisplayName("idle time never fills the bucket beyond capacity")
    void capacityIsCapped() {
        RateLimitPolicy policy = new RateLimitPolicy("burst", 3, Duration.ofSeconds(60), false);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("client", policy);
        }
        clock.advance(Duration.ofHours(1));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("client", policy).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("client", policy).allowed()).isFalse();
    }

    @Test
    @DisplayName("different client keys have independent buckets")
    void keysAreIndependent() {
        RateLimitPolicy policy = new RateLimitPolicy("burst", 1, Duration.ofMinutes(1), false);

        assertThat(limiter.tryAcquire("client-a", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("client-a", policy).allowed()).isFalse();
        assertThat(limiter.tryAcquire("client-b", policy).allowed()).isTrue();
    }

    @Test
    @DisplayName("the same client is independent across policy names")
    void policiesAreNamespaced() {
        RateLimitPolicy burst = new RateLimitPolicy("burst", 1, Duration.ofMinutes(1), false);
        RateLimitPolicy daily = new RateLimitPolicy("daily", 1, Duration.ofHours(24), false);

        assertThat(limiter.tryAcquire("client", burst).allowed()).isTrue();
        assertThat(limiter.tryAcquire("client", burst).allowed()).isFalse();
        assertThat(limiter.tryAcquire("client", daily).allowed()).isTrue();
    }

    @Test
    @DisplayName("concurrent check-and-consume never over-admits")
    void concurrentConsumptionIsAtomic() throws Exception {
        int limit = 100;
        int threads = 8;
        int attemptsPerThread = 50;
        RateLimitPolicy policy = new RateLimitPolicy("burst", limit, Duration.ofHours(1), false);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (limiter.tryAcquire("shared", policy).allowed()) {
                            allowed.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(limit);
        assertThat(rejected.get()).isEqualTo(threads * attemptsPerThread - limit);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
