package com.url.shortly.service;

/** Paid-only breakdowns over recorded click dimensions. */
public interface AdvancedAnalyticsService {

    LinkAdvancedAnalytics analytics(Long linkId, int days);
}
