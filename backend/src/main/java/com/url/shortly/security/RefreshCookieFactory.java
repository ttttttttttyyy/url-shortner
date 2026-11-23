package com.url.shortly.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.url.shortly.config.AppProperties;

/**
 * Builds the refresh-token cookie.
 *
 * <p>The cookie is host-only (no {@code Domain}), {@code HttpOnly},
 * {@code Secure} outside local development, {@code SameSite=Lax} and scoped to
 * the authentication path. Scoping it to {@code /api/v1/auth} means it is not
 * sent to link or redirect endpoints at all.
 */
@Component
public class RefreshCookieFactory {

    private final AppProperties properties;

    public RefreshCookieFactory(AppProperties properties) {
        this.properties = properties;
    }

    public String cookieName() {
        return properties.security().auth().refreshCookieName();
    }

    public ResponseCookie create(String rawRefreshToken) {
        AppProperties.Security.Auth config = properties.security().auth();
        Duration maxAge = properties.security().jwt().refreshTokenTtl();

        return ResponseCookie.from(config.refreshCookieName(), rawRefreshToken)
                .httpOnly(true)
                .secure(config.refreshCookieSecure())
                .path(config.refreshCookiePath())
                .sameSite(config.refreshCookieSameSite())
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie clear() {
        AppProperties.Security.Auth config = properties.security().auth();

        return ResponseCookie.from(config.refreshCookieName(), "")
                .httpOnly(true)
                .secure(config.refreshCookieSecure())
                .path(config.refreshCookiePath())
                .sameSite(config.refreshCookieSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }
}
