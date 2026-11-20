package com.url.shortly.controller;

import java.net.URI;
import java.time.Clock;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.DTO.CreateLinkResponse;
import com.url.shortly.DTO.LinkDashboardResponse;
import com.url.shortly.DTO.LinkDetailResponse;
import com.url.shortly.DTO.UpdateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.ratelimit.RateLimitScope;
import com.url.shortly.ratelimit.RateLimited;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.OwnedLinks;
import com.url.shortly.service.ShortUrlBuilder;
import com.url.shortly.service.exeptions.AccountUnavailableException;

import jakarta.validation.Valid;

/**
 * Link management API. Only creation is implemented so far; the remaining
 * endpoints are placeholders until the ownership/authorization layer lands.
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkApiController {

    private static final int MAX_PAGE_SIZE = 100;

    final LinkService linkService;
    final CallerResolver callerResolver;
    final ShortUrlBuilder shortUrlBuilder;
    final Clock clock;

    LinkApiController(LinkService linkService, CallerResolver callerResolver,
                      ShortUrlBuilder shortUrlBuilder, Clock clock) {

        this.linkService = linkService;
        this.callerResolver = callerResolver;
        this.shortUrlBuilder = shortUrlBuilder;
        this.clock = clock;
    }

    /**
     * Create a short link; guest or authenticated access according to rate and
     * plan rules.
     */
    @Operation(summary = "Create a short link",
            description = "Guest or authenticated access according to rate and plan rules. "
                    + "The returned Location and shortUrl point at the short URL resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Link created"),
            @ApiResponse(responseCode = "400", description = "Malformed or invalid body"),
            @ApiResponse(responseCode = "403", description = "Entitlement required (alias or password)"),
            @ApiResponse(responseCode = "409", description = "Requested alias already taken"),
            @ApiResponse(responseCode = "422", description = "Invalid destination URL or expiration"),
            @ApiResponse(responseCode = "429", description = "Guest or user creation rate limit exceeded"),
            @ApiResponse(responseCode = "503", description = "Short-code space exhausted")
    })
    @RateLimited(RateLimitScope.LINK_CREATION)
    @PostMapping
    public ResponseEntity<CreateLinkResponse> createLink(
            @Valid @RequestBody CreateLinkRequest request) {

        Link link = linkService.createLink(request, callerResolver.current());
        String shortUrl = shortUrlBuilder.build(link.getShortCode());

        return ResponseEntity.created(URI.create(shortUrl))
                .body(CreateLinkResponse.from(link, shortUrl));
    }

    /**
     * Dashboard data: the caller's own links plus an account-wide usage
     * summary. Ownership is taken from the authenticated principal; no client
     * supplied id can widen the result.
     */
    @Operation(summary = "List the authenticated user's links",
            description = "Newest first. The usage summary covers all non-deleted links "
                    + "owned by the caller, independently of page and status filter.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dashboard page"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size or status filter"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    @GetMapping
    public ResponseEntity<LinkDashboardResponse> getLinks(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) LinkStatus status) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (status == LinkStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "deleted links are not listed; omit status to exclude them");
        }

        OwnedLinks result = linkService.listOwnedLinks(authenticatedOwnerId(), page, size, status);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.VARY, "Authorization")
                .body(LinkDashboardResponse.from(result, page, size, shortUrlBuilder));
    }

    /** Read one owned link. */
    @Operation(summary = "Read one owned link")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Link details"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Link not found or not owned by caller")
    })
    @GetMapping("/{linkId}")
    public ResponseEntity<LinkDetailResponse> getLink(@PathVariable Long linkId) {
        Link link = linkService.getOwnedLink(authenticatedOwnerId(), linkId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LinkDetailResponse.from(
                        link, shortUrlBuilder.build(link.getShortCode()), clock.instant()));
    }

    /** Update permitted properties of an owned link. */
    @Operation(summary = "Update an owned link",
            description = "Only url, expiresAt and active may be changed. Aliases and passwords "
                    + "are paid features and are rejected until FR-08/FR-13 ship.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated link details"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Alias or password requires a paid plan"),
            @ApiResponse(responseCode = "404", description = "Link not found or not owned by caller"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification"),
            @ApiResponse(responseCode = "422", description = "Invalid update or destination")
    })
    @PatchMapping("/{linkId}")
    public ResponseEntity<LinkDetailResponse> updateLink(@PathVariable Long linkId,
            @RequestBody UpdateLinkRequest request) {
        Link link = linkService.updateOwnedLink(authenticatedOwnerId(), linkId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LinkDetailResponse.from(
                        link, shortUrlBuilder.build(link.getShortCode()), clock.instant()));
    }

    /** Soft-delete an owned link; the short code stays reserved. */
    @Operation(summary = "Delete an owned link",
            description = "Reversible soft delete: the link is hidden from the dashboard and "
                    + "redirects but its row and short code are retained.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Link deleted (idempotent)"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Link not found or not owned by caller")
    })
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long linkId) {
        linkService.deleteOwnedLink(authenticatedOwnerId(), linkId);
        return ResponseEntity.noContent().build();
    }

    /** Return authorized analytics for a link. */
    @GetMapping("/{linkId}/analytics")
    public ResponseEntity<Void> getAnalytics(@PathVariable Long linkId) {
        // TODO: verify ownership and return analytics within the configured retention.
        return notImplemented();
    }

    /** Generate or retrieve the QR code for an authorized link. */
    @GetMapping("/{linkId}/qr")
    public ResponseEntity<Void> getQrCode(@PathVariable Long linkId) {
        // TODO: verify ownership and generate/return the QR code image.
        return notImplemented();
    }

    /**
     * Ownership always comes from the authenticated principal. Spring Security
     * rejects anonymous requests first; this guard also covers test/mock
     * principals that carry no account id.
     */
    private Long authenticatedOwnerId() {
        CallerContext caller = callerResolver.current();
        if (caller == null || caller.userId() == null) {
            throw new AccountUnavailableException();
        }
        return caller.userId();
    }

    private ResponseEntity<Void> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
