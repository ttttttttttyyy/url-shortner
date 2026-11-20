package com.url.shortly.DTO;

import java.util.List;

import com.url.shortly.service.LinkAnalytics;

/**
 * Basic analytics response. Advanced breakdowns (FR-10) are intentionally
 * absent.
 */
public record AnalyticsResponse(
        Long linkId,
        long totalClicks,
        long uniqueClicks,
        int rangeDays,
        long uniqueWindowSeconds,
        List<DailyClicksResponse> series) {

    public static AnalyticsResponse from(LinkAnalytics analytics) {
        return new AnalyticsResponse(
                analytics.linkId(),
                analytics.totalClicks(),
                analytics.uniqueClicks(),
                analytics.rangeDays(),
                analytics.uniqueWindowSeconds(),
                analytics.series().stream().map(DailyClicksResponse::from).toList());
    }
}
