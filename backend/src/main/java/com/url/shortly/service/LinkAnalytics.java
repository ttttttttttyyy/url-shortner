package com.url.shortly.service;

import java.time.Instant;
import java.util.List;

/**
 * Basic analytics for one link (FR-09).
 *
 * @param totalClicks        all eligible visits in the reporting range
 * @param uniqueClicks       estimated distinct visits, deduplicating one visitor
 *                           within {@code uniqueWindowSeconds}
 * @param series             daily buckets covering the whole range, including
 *                           days with no visits
 */
public record LinkAnalytics(
        Long linkId,
        long totalClicks,
        long uniqueClicks,
        int rangeDays,
        long uniqueWindowSeconds,
        List<DailyClicks> series) {

    public record DailyClicks(Instant day, long clicks, long uniqueClicks) {
    }
}
