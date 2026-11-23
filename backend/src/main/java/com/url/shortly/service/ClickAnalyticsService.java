package com.url.shortly.service;

/** Records eligible visits and reports basic analytics for an owned link. */
public interface ClickAnalyticsService {

    /** Best-effort recording; the redirect must succeed even if analytics fail. */
    void record(Long linkId, ClickMetadata metadata);

    LinkAnalytics analytics(Long linkId, int days);
}
