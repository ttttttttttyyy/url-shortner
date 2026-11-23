package com.url.shortly.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.url.shortly.config.AppProperties;
import com.url.shortly.security.TokenHasher;
import com.url.shortly.service.exeptions.RateLimitExceededException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Per-account login throttling.
 *
 * <p>The address-based limit runs in {@link RateLimitInterceptor} before the
 * request body is bound. This guard adds the second key: a hash of the
 * normalized email, shared across all addresses. Both buckets consume on every
 * login attempt, success or failure; there is no permanent lockout.
 */
@Component
public class AuthRateLimitGuard {

    private final AppProperties properties;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AuthRateLimitGuard(AppProperties properties,
                              RateLimiter rateLimiter,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    public void checkLoginAccount(String normalizedEmail) {
        AppProperties.Security security = properties.security();
        if (security == null || security.rateLimit() == null
                || !security.rateLimit().enabled()) {
            return;
        }

        AppProperties.Security.RateLimit config = security.rateLimit();
        if (config.loginAttempts() <= 0 || config.window() == null
                || config.window().isZero() || config.window().isNegative()) {
            return;
        }

        RateLimitPolicy policy = new RateLimitPolicy(
                RateLimitPolicyResolver.AUTH_LOGIN_ACCOUNT,
                config.loginAttempts(),
                config.window(),
                false);
        String key = "email:" + TokenHasher.sha256Hex(normalizedEmail);

        RateLimitDecision decision = rateLimiter.tryAcquire(key, policy);
        if (!decision.allowed()) {
            recordRejection(policy.name());
            throw new RateLimitExceededException(
                    policy.name(), decision.retryAfterSeconds());
        }
    }

    private void recordRejection(String policy) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter(RateLimitInterceptor.REJECTED_METRIC, "policy", policy).increment();
        }
    }
}
