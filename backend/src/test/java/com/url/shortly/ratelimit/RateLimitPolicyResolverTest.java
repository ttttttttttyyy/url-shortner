package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;

/**
 * FR-04: policy composition for guests, authenticated users and the optional
 * per-instance safety valve.
 */
class RateLimitPolicyResolverTest {

    @Test
    @DisplayName("a guest gets burst and 24h policies; the global cap is off by default")
    void guestPolicies() {
        RateLimitPolicyResolver resolver = resolver(true, Duration.ofMinutes(1), 5, 50, 0, 30);

        List<RateLimitPolicy> policies = resolver.policiesFor(
                RateLimitScope.LINK_CREATION, CallerContext.guest());

        assertThat(policies).extracting(RateLimitPolicy::name)
                .containsExactly("guest-link-creation", "guest-link-creation-daily");
        assertThat(policies.get(0).limit()).isEqualTo(5);
        assertThat(policies.get(0).window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(policies.get(0).global()).isFalse();
        assertThat(policies.get(1).limit()).isEqualTo(50);
        assertThat(policies.get(1).window()).isEqualTo(Duration.ofHours(24));
        assertThat(policies.get(1).global()).isFalse();
    }

    @Test
    @DisplayName("the optional global cap is appended as a shared bucket")
    void globalPolicyIsOptional() {
        RateLimitPolicyResolver resolver = resolver(true, Duration.ofMinutes(1), 5, 50, 1000, 30);

        List<RateLimitPolicy> policies = resolver.policiesFor(
                RateLimitScope.LINK_CREATION, CallerContext.guest());

        assertThat(policies).extracting(RateLimitPolicy::name)
                .containsExactly("guest-link-creation", "guest-link-creation-daily",
                        "guest-link-creation-global");
        assertThat(policies.get(2).limit()).isEqualTo(1000);
        assertThat(policies.get(2).global()).isTrue();
    }

    @Test
    @DisplayName("a zero daily limit disables only the daily policy")
    void zeroDailyLimitIsDisabled() {
        RateLimitPolicyResolver resolver = resolver(true, Duration.ofMinutes(1), 5, 0, 0, 30);

        assertThat(resolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .extracting(RateLimitPolicy::name)
                .containsExactly("guest-link-creation");
    }

    @Test
    @DisplayName("authenticated callers use the per-user policy, not guest buckets")
    void authenticatedPolicies() {
        RateLimitPolicyResolver resolver = resolver(true, Duration.ofMinutes(1), 5, 50, 0, 30);

        List<RateLimitPolicy> policies = resolver.policiesFor(
                RateLimitScope.LINK_CREATION, new CallerContext(7L, PlanTier.FREE));

        assertThat(policies).extracting(RateLimitPolicy::name)
                .containsExactly("user-link-creation");
        assertThat(policies.get(0).limit()).isEqualTo(30);
        assertThat(policies.get(0).global()).isFalse();
    }

    @Test
    @DisplayName("a missing caller is treated as a guest")
    void missingCallerIsGuest() {
        RateLimitPolicyResolver resolver = resolver(true, Duration.ofMinutes(1), 5, 50, 0, 30);

        assertThat(resolver.policiesFor(RateLimitScope.LINK_CREATION, null))
                .extracting(RateLimitPolicy::name)
                .containsExactly("guest-link-creation", "guest-link-creation-daily");
    }

    @Test
    @DisplayName("disabling the feature disables every policy")
    void disabledFeature() {
        RateLimitPolicyResolver resolver = resolver(false, Duration.ofMinutes(1), 5, 50, 1000, 30);

        assertThat(resolver.policiesFor(RateLimitScope.LINK_CREATION, CallerContext.guest()))
                .isEmpty();
    }

    private static RateLimitPolicyResolver resolver(boolean enabled, Duration window,
                                                    int guest, int daily, int global, int user) {
        AppProperties.Security.RateLimit rateLimit = new AppProperties.Security.RateLimit(
                enabled, window, guest, daily, global, user, 10, 10);
        AppProperties.Security security = new AppProperties.Security(null, null, rateLimit);
        AppProperties properties = new AppProperties(
                "http://localhost:10004", null, null, security, null, null, null);
        return new RateLimitPolicyResolver(properties);
    }
}
