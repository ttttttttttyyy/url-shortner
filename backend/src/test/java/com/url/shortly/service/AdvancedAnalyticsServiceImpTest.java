package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.url.shortly.Entities.ClickEvent;
import com.url.shortly.Repository.ClickEventRepository;
import com.url.shortly.service.imp.AdvancedAnalyticsServiceImp;

/** FR-10: breakdown aggregation, ordering, missing dimensions and capping. */
@ExtendWith(MockitoExtension.class)
class AdvancedAnalyticsServiceImpTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Mock
    private ClickEventRepository clicks;

    private AdvancedAnalyticsServiceImp service;

    @BeforeEach
    void setUp() {
        service = new AdvancedAnalyticsServiceImp(clicks,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Breakdowns are sorted by clicks and missing countries are omitted")
    void breakdowns() {
        givenEvents(List.of(
                event("US", "Desktop", "Windows", "Chrome", "google.com"),
                event("US", "Desktop", "Windows", "Chrome", "google.com"),
                event("FR", "Mobile", "Android", "Chrome", null),
                event(null, "Unknown", "Unknown", "Unknown", null)));

        LinkAdvancedAnalytics result = service.analytics(7L, 7);

        assertThat(result.linkId()).isEqualTo(7L);
        assertThat(result.rangeDays()).isEqualTo(7);
        assertThat(result.countries()).containsExactly(
                new LinkAdvancedAnalytics.Breakdown("US", 2),
                new LinkAdvancedAnalytics.Breakdown("FR", 1));
        assertThat(result.devices()).containsExactly(
                new LinkAdvancedAnalytics.Breakdown("Desktop", 2),
                new LinkAdvancedAnalytics.Breakdown("Mobile", 1),
                new LinkAdvancedAnalytics.Breakdown("Unknown", 1));
        assertThat(result.browsers()).containsExactly(
                new LinkAdvancedAnalytics.Breakdown("Chrome", 3),
                new LinkAdvancedAnalytics.Breakdown("Unknown", 1));
        assertThat(result.referrers()).containsExactly(
                new LinkAdvancedAnalytics.Breakdown("Direct", 2),
                new LinkAdvancedAnalytics.Breakdown("google.com", 2));
    }

    @Test
    @DisplayName("Long tails are capped into an Other bucket")
    void capsLongTail() {
        List<ClickEvent> events = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(event("US", "Desktop", "Windows", "Chrome", "host" + i + ".example"));
        }
        givenEvents(events);

        LinkAdvancedAnalytics result = service.analytics(7L, 7);

        assertThat(result.referrers()).hasSize(21);
        assertThat(result.referrers().get(20))
                .isEqualTo(new LinkAdvancedAnalytics.Breakdown("Other", 5));
    }

    private void givenEvents(List<ClickEvent> events) {
        when(clicks.findByLinkIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                eq(7L), any())).thenReturn(events);
    }

    private static ClickEvent event(String country, String device, String os,
                                    String browser, String referrer) {
        ClickEvent event = new ClickEvent();
        event.setOccurredAt(NOW.minus(Duration.ofHours(1)));
        event.setCountry(country);
        event.setDeviceType(device);
        event.setOperatingSystem(os);
        event.setBrowser(browser);
        event.setReferrer(referrer);
        return event;
    }
}
