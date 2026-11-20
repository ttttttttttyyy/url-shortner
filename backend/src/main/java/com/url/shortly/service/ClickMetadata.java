package com.url.shortly.service;

/**
 * Non-identifying metadata captured with an eligible redirect.
 *
 * @param visitorHash salted hash of the client address, never the raw address
 */
public record ClickMetadata(String visitorHash, String referrer, String userAgent) {
}
