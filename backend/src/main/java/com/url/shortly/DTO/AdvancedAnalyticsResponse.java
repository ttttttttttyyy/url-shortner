package com.url.shortly.DTO;

import java.util.List;

import com.url.shortly.service.LinkAdvancedAnalytics;

/** Paid-only breakdown response for one link (FR-10). */
public record AdvancedAnalyticsResponse(
        Long linkId,
        int rangeDays,
        List<BreakdownResponse> countries,
        List<BreakdownResponse> devices,
        List<BreakdownResponse> operatingSystems,
        List<BreakdownResponse> browsers,
        List<BreakdownResponse> referrers) {

    public record BreakdownResponse(String value, long clicks) {
    }

    public static AdvancedAnalyticsResponse from(LinkAdvancedAnalytics analytics) {
        return new AdvancedAnalyticsResponse(
                analytics.linkId(),
                analytics.rangeDays(),
                map(analytics.countries()),
                map(analytics.devices()),
                map(analytics.operatingSystems()),
                map(analytics.browsers()),
                map(analytics.referrers()));
    }

    private static List<BreakdownResponse> map(List<LinkAdvancedAnalytics.Breakdown> entries) {
        return entries.stream()
                .map(entry -> new BreakdownResponse(entry.value(), entry.clicks()))
                .toList();
    }
}
