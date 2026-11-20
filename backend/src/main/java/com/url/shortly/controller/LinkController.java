package com.url.shortly.controller;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.ratelimit.ClientIpResolver;
import com.url.shortly.service.ClickAnalyticsService;
import com.url.shortly.service.ClickMetadata;
import com.url.shortly.service.GeoResolver;
import com.url.shortly.service.LinkPasswordService;
import com.url.shortly.service.LinkResolution;
import com.url.shortly.service.LinkResolution.Outcome;
import com.url.shortly.service.LinkService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Public short-link entry point (SoW FR-03).
 *
 * <p>The path pattern doubles as a route guard: only 1-32 characters from the
 * code alphabet reach the database. Requests such as {@code /favicon.ico},
 * {@code /robots.txt}, {@code /abc/} and over-long codes fall through to the
 * framework's 404 without a lookup.
 */
@RestController
public class LinkController {

    private static final Logger log = LoggerFactory.getLogger(LinkController.class);

    private static final String SHORT_CODE_MAPPING = "/{shortCode:[A-Za-z0-9_-]{1,32}}";

    private final LinkService linkService;
    private final LinkStatusPage statusPage;
    private final ClickAnalyticsService clickAnalytics;
    private final ClientIpResolver clientIpResolver;
    private final GeoResolver geoResolver;
    private final LinkPasswordService linkPasswords;
    private static final String ACCESS_PARAM = "access";
    private static final String ACCESS_COOKIE = "SHORTLY_LINK_ACCESS";

    LinkController(LinkService linkService, LinkStatusPage statusPage,
                   ClickAnalyticsService clickAnalytics, ClientIpResolver clientIpResolver,
                   GeoResolver geoResolver, LinkPasswordService linkPasswords) {
        this.linkService = linkService;
        this.statusPage = statusPage;
        this.clickAnalytics = clickAnalytics;
        this.clientIpResolver = clientIpResolver;
        this.geoResolver = geoResolver;
        this.linkPasswords = linkPasswords;
    }

    @Operation(summary = "Resolve a short link",
            description = "Redirects ACTIVE links with 302. Every other outcome returns an HTML status page.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirect to the saved destination"),
            @ApiResponse(responseCode = "401", description = "Password required (FR-13 placeholder)"),
            @ApiResponse(responseCode = "403", description = "Link disabled or blocked"),
            @ApiResponse(responseCode = "404", description = "Short code not found"),
            @ApiResponse(responseCode = "410", description = "Link expired or deleted"),
            @ApiResponse(responseCode = "500", description = "Stored destination is malformed")
    })
    @GetMapping(SHORT_CODE_MAPPING)
    public ResponseEntity<?> resolveShortLink(@PathVariable String shortCode,
                                              HttpServletRequest request) {
        LinkResolution resolution = linkService.resolve(shortCode);

        // A valid link-access token only overrides PASSWORD_REQUIRED. Every
        // other status (deleted, blocked, disabled, expired) still wins.
        if (resolution.outcome() == Outcome.PASSWORD_REQUIRED
                && linkPasswords.tokenValid(accessToken(request), resolution.link())) {
            resolution = new LinkResolution(resolution.link(), Outcome.ACTIVE);
        }

        if (!resolution.redirects()) {
            return statusPage.render(resolution.outcome());
        }

        URI location;
        try {
            location = URI.create(resolution.link().getUrl());
        } catch (IllegalArgumentException ex) {
            // Rows written before FR-02 could contain a malformed destination.
            // Keep the failure contained: no destination in the response or log.
            log.error("Stored destination for short code {} is not a valid URI", shortCode);
            return statusPage.renderInternalError();
        }

        // Analytics must never turn a valid redirect into a failure. Only
        // eligible GET redirects (ACTIVE, unexpired, no password) are counted;
        // Spring maps HEAD to this handler, and a probe is not a visit.
        if (HttpMethod.GET.matches(request.getMethod())) {
            try {
                clickAnalytics.record(resolution.link().getId(), new ClickMetadata(
                        clientIpResolver.key(request),
                        request.getHeader(HttpHeaders.REFERER),
                        request.getHeader(HttpHeaders.USER_AGENT),
                        geoResolver.countryFor(request)));
            } catch (RuntimeException ex) {
                log.warn("Could not record click for short code {}", shortCode, ex);
            }
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(location)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .build();
    }

    /** Token from the query string (portable) or the scoped HttpOnly cookie. */
    private static String accessToken(HttpServletRequest request) {
        String token = request.getParameter(ACCESS_PARAM);
        if (token != null && !token.isBlank()) {
            return token;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ACCESS_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
