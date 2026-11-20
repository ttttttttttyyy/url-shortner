package com.url.shortly.service;

/**
 * Non-identifying metadata captured with an eligible redirect.
 *
 * @param visitorHash salted hash of the client address, never the raw address
 * @param referrer    raw referrer header; the service stores only its host
 * @param userAgent   raw user-agent header; the service derives coarse classes
 * @param country     country from a geo resolver, or {@code null} when no
 *                    provider is available (FR-10)
 */
public record ClickMetadata(String visitorHash, String referrer, String userAgent,
                            String country) {
}
