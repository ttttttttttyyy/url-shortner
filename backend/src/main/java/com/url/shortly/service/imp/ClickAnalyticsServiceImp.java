package com.url.shortly.service.imp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.url.shortly.Entities.ClickEvent;
import com.url.shortly.Repository.ClickEventRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.service.ClickAnalyticsService;
import com.url.shortly.service.ClickMetadata;
import com.url.shortly.service.LinkAnalytics;

/**
 * FR-09: best-effort click recording and basic analytics aggregation.
 *
 * <p>Aggregation runs in Java over the reporting window. That keeps the query
 * portable between PostgreSQL and the H2 test database; FR-19 can move it to
 * SQL rollups when click volume warrants it.
 */
@Service
public class ClickAnalyticsServiceImp implements ClickAnalyticsService {

    private static final Duration DEFAULT_UNIQUE_WINDOW = Duration.ofHours(24);
    private static final int MAX_TEXT_LENGTH = 512;
    private static final String UNKNOWN_VISITOR = "unknown";

    private final ClickEventRepository clicks;
    private final AppProperties properties;
    private final Clock clock;

    public ClickAnalyticsServiceImp(ClickEventRepository clicks,
                                    AppProperties properties,
                                    Clock clock) {
        this.clicks = clicks;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(Long linkId, ClickMetadata metadata) {
        ClickEvent event = new ClickEvent();
        event.setLinkId(linkId);
        event.setOccurredAt(clock.instant());
        event.setVisitorHash(truncate(
                metadata.visitorHash() == null ? UNKNOWN_VISITOR : metadata.visitorHash(), 64));
        event.setReferrer(truncate(metadata.referrer(), MAX_TEXT_LENGTH));
        event.setUserAgent(truncate(metadata.userAgent(), MAX_TEXT_LENGTH));
        clicks.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public LinkAnalytics analytics(Long linkId, int days) {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(days));
        Duration window = uniqueWindow();

        List<ClickEvent> events =
                clicks.findByLinkIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(linkId, since);

        long total = 0;
        long unique = 0;
        Map<String, Instant> lastCountedVisit = new HashMap<>();
        Map<LocalDate, long[]> byDay = new TreeMap<>();

        for (ClickEvent event : events) {
            total++;
            LocalDate day = LocalDate.ofInstant(event.getOccurredAt(), ZoneOffset.UTC);
            long[] bucket = byDay.computeIfAbsent(day, ignored -> new long[2]);
            bucket[0]++;

            Instant previous = lastCountedVisit.get(event.getVisitorHash());
            if (previous == null || !event.getOccurredAt().isBefore(previous.plus(window))) {
                unique++;
                bucket[1]++;
                lastCountedVisit.put(event.getVisitorHash(), event.getOccurredAt());
            }
        }

        List<LinkAnalytics.DailyClicks> series = new ArrayList<>();
        LocalDate firstDay = LocalDate.ofInstant(since, ZoneOffset.UTC);
        LocalDate lastDay = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            long[] bucket = byDay.getOrDefault(day, new long[] { 0L, 0L });
            series.add(new LinkAnalytics.DailyClicks(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant(), bucket[0], bucket[1]));
        }

        return new LinkAnalytics(linkId, total, unique, days, window.toSeconds(), series);
    }

    private Duration uniqueWindow() {
        Duration configured = properties.analytics() == null
                ? null
                : properties.analytics().uniqueClickWindow();
        return configured == null || configured.isZero() || configured.isNegative()
                ? DEFAULT_UNIQUE_WINDOW
                : configured;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
