package com.url.shortly.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Seam for resolving a country from the redirect request.
 *
 * <p>No GeoIP provider is selected yet, so the default implementation returns
 * {@code null}. Implementations must return an ISO-3166 alpha-2 code (case is
 * normalized at recording). FR-10 breakdowns omit the country dimension when
 * no data is available, as the SoW requires. A provider-backed bean can replace
 * this one without changing the analytics API.
 */
public interface GeoResolver {

    String countryFor(HttpServletRequest request);
}
