package com.url.shortly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.DTO.UpdateLinkRequest;

/**
 * Link management API. Every endpoint is a placeholder until the persistence
 * and authorization layers are wired in.
 */
@RestController
@RequestMapping("/api/v1/links")
public class LinkApiController {

    /**
     * Create a short link; guest or authenticated access according to rate and
     * plan rules.
     */
    @PostMapping
    public ResponseEntity<Void> createLink(@RequestBody CreateLinkRequest request) {
        // TODO: resolve guest/authenticated caller, enforce rate + plan limits,
        // generate a short code and persist the link.
        return notImplemented();
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
