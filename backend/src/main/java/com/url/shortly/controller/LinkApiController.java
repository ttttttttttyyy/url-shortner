package com.url.shortly.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.DTO.CreateLinkResponse;
import com.url.shortly.DTO.UpdateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.ratelimit.RateLimitScope;
import com.url.shortly.ratelimit.RateLimited;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.ShortUrlBuilder;

import jakarta.validation.Valid;

/**
 * Link management API. Only creation is implemented so far; the remaining
 * endpoints are placeholders until the ownership/authorization layer lands.
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkApiController {

    final LinkService linkService;
    final CallerResolver callerResolver;
    final ShortUrlBuilder shortUrlBuilder;

    LinkApiController(LinkService linkService, CallerResolver callerResolver, ShortUrlBuilder shortUrlBuilder) {
        
        this.linkService = linkService;
        this.callerResolver = callerResolver;
        this.shortUrlBuilder = shortUrlBuilder;
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

    /** List the authenticated user's own links. */
    @GetMapping
    public ResponseEntity<Void> getLinks() {
        // TODO: return only the authenticated user's links.
        return notImplemented();
    }

    /** Read one owned link. */
    @GetMapping("/{linkId}")
    public ResponseEntity<Void> getLink(@PathVariable Long linkId) {
        // TODO: load the link and verify ownership.
        return notImplemented();
    }

    /** Update permitted properties of an owned link. */
    @PatchMapping("/{linkId}")
    public ResponseEntity<Void> updateLink(@PathVariable Long linkId,
            @RequestBody UpdateLinkRequest request) {
        // TODO: verify ownership, then update only permitted properties.
        return notImplemented();
    }

    /** Delete or deactivate an owned link according to retention policy. */
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long linkId) {
        // TODO: verify ownership, then delete or deactivate per retention policy.
        return notImplemented();
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

    private ResponseEntity<Void> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
