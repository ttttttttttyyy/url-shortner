package com.url.shortly.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.url.shortly.config.AppProperties;
import com.url.shortly.service.exeptions.InvalidUrlException;

/**
 * Validates and normalizes destination URLs (FR-02).
 *
 * <p>Rules: http/https schemes only, host required, no credentials, no
 * self-shortening, bounded length after ASCII encoding.
 *
 * <p><strong>Path policy (accepted product decision):</strong> the path is
 * intentionally normalized with {@link URI#normalize()} — dot segments
 * collapse (RFC 3986 §5.2.4) and Java additionally collapses empty segments
 * ({@code /a//b -> /a/b}), which is Java behavior, not an RFC requirement.
 * This keeps stored links clean, but it may rewrite destinations whose path
 * identity depends on empty segments (for example pre-signed S3 URLs). If
 * that ever matters, replace {@code uri.normalize().toASCIIString()} with
 * {@code uri.toASCIIString()} and update the pinning tests in
 * {@code UrlValidatorTest}.
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED = Set.of("http", "https");

    /** Keep in sync with {@code @Size(max = 2048)} on {@code CreateLinkRequest.url};
     * bean-validation annotations need a compile-time constant, so the value
     * is duplicated here and checked again after percent-encoding. */
    private static final int MAX_LENGTH = 2048;

    /** DNS label: alphanumeric, may contain inner hyphens, 1–63 characters. */
    private static final String LABEL = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?";

    private final String shortOrigin;

    @Autowired
    public UrlValidator(AppProperties properties) {
        this(properties.baseUrl());
    }

    UrlValidator(String baseUrl) {
        this.shortOrigin = originOf(URI.create(baseUrl));
        if (this.shortOrigin == null) {
            throw new IllegalStateException(
                    "app.base-url must be an absolute URL with a host");
        }
    }

    /** @return the normalized URL to store and redirect to */
    public String validateAndNormalize(String raw) {

        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("Destination URL is required");
        }

        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException ex) {
            throw new InvalidUrlException("Destination URL is not a valid URI");
        }

        String scheme = uri.getScheme();

        if (scheme == null || !ALLOWED.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException("Only http and https URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("Destination URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidUrlException("Destination URL must not contain credentials");
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            throw new InvalidUrlException("Destination URL has an invalid port");
        }
        if (shortOrigin.equals(originOf(uri))) {
            throw new InvalidUrlException("Destination URL cannot point at the shortener itself");
        }
        validateHostname(uri.getHost());

        String normalized = uri.normalize().toASCIIString();

        if (normalized.length() > MAX_LENGTH) {
            throw new InvalidUrlException(
                    "Destination URL must be at most " + MAX_LENGTH + " characters");
        }
        return normalized;
    }

    /**
     * Origin (scheme + lowercase host + effective port) of a URI, or
     * {@code null} when scheme or host are missing.
     *
     * <p>Implicit ports are normalized to scheme defaults (https → 443,
     * http → 80), so {@code https://sho.rt} and {@code https://sho.rt:443}
     * are the same origin, while {@code http://localhost:3000} differs from
     * {@code http://localhost:10004} in development.
     *
     * <p>Trailing dots are stripped ({@code sho.rt.} is the same DNS host as
     * {@code sho.rt}), so {@code https://sho.rt./abc} cannot bypass the
     * self-shortening check.
     */
    private static String originOf(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }

        host = host.toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");

        int port = uri.getPort();
        if (port == -1) {
            port = switch (scheme.toLowerCase(Locale.ROOT)) {
                case "https" -> 443;
                case "http" -> 80;
                default -> -1;
            };
        }

        String authority = host + (port == -1 ? "" : ":" + port);
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority;
    }

    /**
     * DNS host-name sanity check. Java's URI already rejects malformed hosts
     * ({@code getHost() == null}), so this is defense-in-depth against JDK
     * leniency differences. Bracketed IPv6 literals are exempt; the scheme,
     * port and credential rules still apply to them.
     */
    private static void validateHostname(String host) {
        if (host == null || host.startsWith("[")) {
            return;
        }
        for (String label : host.split("\\.")) {
            if (!label.matches(LABEL)) {
                throw new InvalidUrlException("Destination URL has an invalid host name");
            }
        }
    }
}
