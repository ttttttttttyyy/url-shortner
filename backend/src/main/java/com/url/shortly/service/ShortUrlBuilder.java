package com.url.shortly.service;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.url.shortly.config.AppProperties;

/**
 * Builds the public short URL for a code from the configured base URL.
 *
 * <p>The base URL comes from {@code app.base-url} (for example
 * {@code https://sho.rt}), never from the request's {@code Host} header,
 * which would be host-header injection.
 *
 * <p>The API may be served from a different host (for example
 * {@code https://app.sho.rt/api}); the short URL is always
 * {@code {base-url}/{code}} regardless of which host received the request.
 */
@Component
public class ShortUrlBuilder {

    private final String baseUrl;

    public ShortUrlBuilder(AppProperties properties) {
        this.baseUrl = properties.baseUrl();
    }

    /** @return the absolute short URL, e.g. {@code https://sho.rt/aB3xK9} */
    public String build(String code) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment(code)
                .build()
                .toUriString();
    }
}
