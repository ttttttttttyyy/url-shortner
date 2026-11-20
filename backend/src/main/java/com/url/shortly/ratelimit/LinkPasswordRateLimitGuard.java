package com.url.shortly.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.url.shortly.config.AppProperties;
import com.url.shortly.security.TokenHasher;
import com.url.shortly.service.exeptions.RateLimitExceededException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Per-link password-attempt limit (FR-13).
 *
 * <p>The client-address bucket is enforced by {@link RateLimitInterceptor}
 * before body binding. This guard adds a key derived from the short code, so a
 * distributed attacker cannot brute-force one link from many addresses.
 */
@Component
public class LinkPasswordRateLimitGuard {

    private final AppProperties properties;
    private final RateLimiter rateLimiter;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public LinkPasswordRateLimitGuard(AppProperties properties,
                                      RateLimiter rateLimiter,
                                      ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    public void check(String shortCode) {
        AppProperties.Security security = properties.security();
        if (security == null || security.rateLimit() == null
                || !security.rateLimit().enabled()) {
            return;
        }

        AppProperties.Security.RateLimit config = security.rateLimit();
        if (config.passwordVerification() <= 0 || config.window() == null
                || config.window().isZero() || config.window().isNegative()) {
            return;
        }

        RateLimitPolicy policy = new RateLimitPolicy(
                RateLimitPolicyResolver.LINK_PASSWORD_VERIFY,
                config.passwordVerification(),
                config.window(),
                false);
        String key = "linkpass:" + TokenHasher.sha256Hex(shortCode);

        if (!rateLimiter.tryAcquire(key, policy).allowed()) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter(RateLimitInterceptor.REJECTED_METRIC,
                        "policy", policy.name()).increment();
            }
            throw new RateLimitExceededException(policy.name(), config.window().toSeconds());
        }
    }
}
