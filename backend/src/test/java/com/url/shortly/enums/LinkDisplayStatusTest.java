package com.url.shortly.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.url.shortly.Entities.LinkStatus;

/**
 * FR-06: the dashboard's derived status must agree with the summary's
 * active/expired split. Expiry is derived from the instant, never stored.
 */
class LinkDisplayStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    @DisplayName("ACTIVE with no expiry stays active")
    void activeWithoutExpiry() {
        assertThat(LinkDisplayStatus.of(LinkStatus.ACTIVE, null, NOW))
                .isEqualTo(LinkDisplayStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE with a future expiry stays active")
    void activeWithFutureExpiry() {
        assertThat(LinkDisplayStatus.of(LinkStatus.ACTIVE, NOW.plusSeconds(1), NOW))
                .isEqualTo(LinkDisplayStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE at the expiry instant is already expired")
    void activeAtExpiryBoundary() {
        assertThat(LinkDisplayStatus.of(LinkStatus.ACTIVE, NOW, NOW))
                .isEqualTo(LinkDisplayStatus.EXPIRED);
    }

    @Test
    @DisplayName("a stored status other than ACTIVE wins over expiry")
    void storedStatusWinsOverExpiry() {
        Instant past = NOW.minusSeconds(60);
        assertThat(LinkDisplayStatus.of(LinkStatus.DISABLED, past, NOW))
                .isEqualTo(LinkDisplayStatus.DISABLED);
        assertThat(LinkDisplayStatus.of(LinkStatus.BLOCKED, past, NOW))
                .isEqualTo(LinkDisplayStatus.BLOCKED);
        assertThat(LinkDisplayStatus.of(LinkStatus.DELETED, past, NOW))
                .isEqualTo(LinkDisplayStatus.DELETED);
    }
}
