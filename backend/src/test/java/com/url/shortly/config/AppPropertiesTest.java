package com.url.shortly.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the SoW baseline configuration is bound correctly: 7-character
 * short codes, a 32-character maximum, the 62-character alphabet, reserved
 * aliases and the default plan limits.
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

        assertThat(shortCode.length()).isEqualTo(7);
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
}
