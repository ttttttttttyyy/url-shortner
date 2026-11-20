package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.url.shortly.Entities.ClickEvent;
import com.url.shortly.Repository.ClickEventRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.service.imp.ClickAnalyticsServiceImp;

/** FR-09: unique-visit deduplication and daily trend aggregation. */
@ExtendWith(MockitoExtension.class)
class ClickAnalyticsServiceImpTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Mock
    private ClickEventRepository clicks;

    private ClickAnalyticsServiceImp service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                "https://sho.rt", null, null, null, null, null,
                new AppProperties.Analytics(Duration.ofHours(24), 365, "country", true));
        service = new ClickAnalyticsServiceImp(clicks, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("clicks within the unique window count once per visitor")
    void deduplicatesUniqueVisits() {
        List<ClickEvent> events = List.of(
                event("A", NOW.minus(Duration.ofDays(5))),
                event("A", NOW.minus(Duration.ofDays(4)).minus(Duration.ofHours(1))),
                event("A", NOW.minus(Duration.ofDays(3))),
                event("B", NOW.minus(Duration.ofMinutes(60))),
                event("B", NOW.minus(Duration.ofMinutes(30))));
        givenEvents(events);

        LinkAnalytics result = service.analytics(7L, 7);

        assertThat(result.totalClicks()).isEqualTo(5);
        assertThat(result.uniqueClicks()).isEqualTo(3);
        assertThat(result.uniqueWindowSeconds()).isEqualTo(24 * 3600);
        assertThat(result.series()).isNotEmpty();
        assertThat(result.series().stream().mapToLong(LinkAnalytics.DailyClicks::clicks).sum())
                .isEqualTo(result.totalClicks());
        assertThat(result.series().stream().mapToLong(LinkAnalytics.DailyClicks::uniqueClicks).sum())
                .isEqualTo(result.uniqueClicks());
    }

    @Test
    @DisplayName("every UTC day in the range appears, including days with no visits")
    void seriesIncludesEmptyDays() {
        givenEvents(List.of(event("A", NOW.minus(Duration.ofDays(3)))));

        LinkAnalytics result = service.analytics(7L, 4);

        // since = NOW - 4d (noon), so the window touches five calendar days.
        assertThat(result.series()).hasSize(5);
        assertThat(result.series())
                .extracting(LinkAnalytics.DailyClicks::clicks)
                .containsExactly(0L, 1L, 0L, 0L, 0L);
        assertThat(result.series().get(1).uniqueClicks()).isEqualTo(1);
        assertThat(result.series().get(4).day())
                .isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
    }

    @Test
    @DisplayName("recording stores a visitor hash and never the raw address")
    void recordingUsesVisitorHash() {
        service.record(7L, new ClickMetadata(
                "hashed-visitor", "https://ref.example/search?q=secret",
                "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0", "US"));

        ArgumentCaptor<ClickEvent> captured = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clicks).save(captured.capture());
        ClickEvent saved = captured.getValue();
        assertThat(saved.getLinkId()).isEqualTo(7L);
        assertThat(saved.getOccurredAt()).isEqualTo(NOW);
        assertThat(saved.getVisitorHash()).isEqualTo("hashed-visitor");
        // The host is kept; the query string (which may carry secrets) is not.
        assertThat(saved.getReferrer()).isEqualTo("ref.example");
        assertThat(saved.getUserAgent()).contains("Chrome/120.0");
        assertThat(saved.getCountry()).isEqualTo("US");
        assertThat(saved.getDeviceType()).isEqualTo("Desktop");
        assertThat(saved.getOperatingSystem()).isEqualTo("Windows");
        assertThat(saved.getBrowser()).isEqualTo("Chrome");
    }

    @Test
    @DisplayName("Country is stored as normalized ISO-3166 alpha-2 or dropped")
    void countryContract() {
        service.record(7L, new ClickMetadata("visitor", null, "Mozilla/5.0 Chrome/120", " us "));
        service.record(7L, new ClickMetadata("visitor", null, "Mozilla/5.0 Chrome/120", "United States"));

        ArgumentCaptor<ClickEvent> captured = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clicks, org.mockito.Mockito.times(2)).save(captured.capture());
        assertThat(captured.getAllValues().get(0).getCountry()).isEqualTo("US");
        assertThat(captured.getAllValues().get(1).getCountry()).isNull();
    }

    private void givenEvents(List<ClickEvent> events) {
        when(clicks.findByLinkIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
                eq(7L), any())).thenReturn(events);
    }

    private static ClickEvent event(String visitor, Instant at) {
        ClickEvent event = new ClickEvent();
        event.setVisitorHash(visitor);
        event.setOccurredAt(at);
        return event;
    }
}
