package com.url.shortly.service;

import java.util.List;

/**
 * Advanced breakdowns for one link (FR-10).
 *
 * <p>Every list is sorted by clicks descending. Categories absent from the
 * data are omitted; country is omitted entirely until a geo provider exists.
 */
public record LinkAdvancedAnalytics(
        Long linkId,
        int rangeDays,
        List<Breakdown> countries,
        List<Breakdown> devices,
        List<Breakdown> operatingSystems,
        List<Breakdown> browsers,
        List<Breakdown> referrers) {

    public record Breakdown(String value, long clicks) {
    }
}
