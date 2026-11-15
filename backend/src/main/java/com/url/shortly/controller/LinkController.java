package com.url.shortly.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.url.shortly.Entities.Link;
import com.url.shortly.service.LinkService;

@RestController
public class LinkController {

    final LinkService linkService;

    LinkController(LinkService linkService) {
        this.linkService = linkService;
    }
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> getOriginalURL(@PathVariable String shortCode) {
        Link link = linkService.getOriginalLink(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND) // 302 redirect
                .location(URI.create(link.getUrl()))
                .build();
    }
}
