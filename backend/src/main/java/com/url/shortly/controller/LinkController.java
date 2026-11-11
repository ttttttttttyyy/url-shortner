package com.url.shortly.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LinkController {

    /**
     * Resolve a public short link.
     *
     * <p>TODO: look the short code up through LinkService, then apply link status,
     * expiry, password and ad-interstitial rules before redirecting.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> getOriginalURL(@PathVariable String shortCode) {

        return ResponseEntity.status(HttpStatus.FOUND) // 302 redirect
                .location(URI.create("https://example.com/"))
                .build();
    }
}
