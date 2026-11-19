package com.url.shortly.security;

/**
 * Non-secret request metadata retained with a refresh session.
 *
 * @param userAgent truncated user-agent, or {@code null}
 * @param ipHash    salted client-address digest, never the raw address
 */
public record ClientMetadata(String userAgent, String ipHash) {

    public static ClientMetadata empty() {
        return new ClientMetadata(null, null);
    }
}
