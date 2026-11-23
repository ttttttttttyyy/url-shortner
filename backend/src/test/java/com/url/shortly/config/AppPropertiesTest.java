package com.url.shortly.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the SoW baseline configuration is bound correctly: tiered short
 * code lengths (guest 6, free 5, paid 4), a 32-character maximum, the
 * 62-character alphabet, reserved aliases and the default plan limits.
 */
@SpringBootTest
@ActiveProfiles("test")
class AppPropertiesTest {

    @Autowired
    private AppProperties appProperties;

    @Test
    @DisplayName("Short-code settings match the configured defaults")
    void shortCodeDefaults() {
        AppProperties.ShortCode shortCode = appProperties.shortCode();

        assertThat(shortCode.guestMinLength()).isEqualTo(6);
        assertThat(shortCode.freeMinLength()).isEqualTo(5);
        assertThat(shortCode.paidMinLength()).isEqualTo(4);
        assertThat(shortCode.maxLength()).isEqualTo(32);
        assertThat(shortCode.alphabet()).hasSize(62);
        assertThat(shortCode.maxGenerationAttempts()).isEqualTo(10);
    }

    @Test
    @DisplayName("Reserved aliases are configured")
    void reservedAliases() {
        assertThat(appProperties.shortCode().reservedAliases())
                .contains("api", "admin", "actuator", "login");
    }

    @Test
    @DisplayName("Link lifecycle limits are configured")
    void linkLimits() {
        assertThat(appProperties.link().defaultExpirationDays()).isZero();
        assertThat(appProperties.link().maxExpirationDays()).isEqualTo(3650);
        assertThat(appProperties.link().password().minLength()).isEqualTo(8);
        assertThat(appProperties.link().password().maxLength()).isEqualTo(128);
    }

    @Test
    @DisplayName("Plan entitlements are configured")
    void planEntitlements() {
        AppProperties.Plans.Plan free = appProperties.plans().free();
        AppProperties.Plans.Plan paid = appProperties.plans().paid();

        assertThat(free.maxLinks()).isEqualTo(100);
        assertThat(free.customAliases()).isFalse();
        assertThat(free.passwordProtection()).isFalse();

        assertThat(paid.maxLinks()).isEqualTo(-1);   // -1 = unlimited
        assertThat(paid.customAliases()).isTrue();
        assertThat(paid.passwordProtection()).isTrue();
    }

    @Test
    @DisplayName("Analytics defaults match the SoW privacy baseline")
    void analyticsDefaults() {
        assertThat(appProperties.analytics().uniqueClickWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(appProperties.analytics().retentionDays()).isEqualTo(365);
        assertThat(appProperties.analytics().geoGranularity()).isEqualTo("country");
        assertThat(appProperties.analytics().anonymizeIp()).isTrue();
    }

    @Test
    @DisplayName("FR-04 rate limits default to burst plus a rolling daily rate")
    void rateLimitDefaults() {
        AppProperties.Security.RateLimit rateLimit = appProperties.security().rateLimit();

        assertThat(rateLimit.enabled()).isTrue();
        assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(rateLimit.guestLinkCreation()).isEqualTo(5);
        assertThat(rateLimit.guestLinkCreationDaily()).isEqualTo(50);
        assertThat(rateLimit.globalGuestLinkCreation()).isZero();
        assertThat(rateLimit.userLinkCreation()).isEqualTo(30);
    }

    @Test
    @DisplayName("FR-05 authentication rate limits are configured")
    void authRateLimitDefaults() {
        AppProperties.Security.RateLimit rateLimit = appProperties.security().rateLimit();

        assertThat(rateLimit.loginIpAttempts()).isEqualTo(60);
        assertThat(rateLimit.loginAttempts()).isEqualTo(10);
        assertThat(rateLimit.registrationAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("Refresh cookies are host-only, path-scoped and SameSite=Lax")
    void authCookieDefaults() {
        AppProperties.Security.Auth auth = appProperties.security().auth();

        assertThat(auth.refreshCookieName()).isEqualTo("SHORTLY_REFRESH");
        assertThat(auth.refreshCookiePath()).isEqualTo("/api/v1/auth");
        assertThat(auth.refreshCookieSameSite()).isEqualTo("Lax");
        // The test profile disables Secure for plain HTTP; production defaults
        // to true (see application.yml).
        assertThat(auth.refreshCookieSecure()).isFalse();
        assertThat(auth.bcryptStrength()).isEqualTo(4);
    }
}
