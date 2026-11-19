package com.url.shortly.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.url.shortly.service.LinkResolution.Outcome;

/**
 * Self-contained HTML pages for FR-03 outcomes that must not redirect.
 *
 * <p>The project deliberately has no template engine: one static page does not
 * justify Thymeleaf. The page never contains the destination URL, the short
 * code, or a moderation reason, so there is no user-controlled value to escape
 * and no enumeration or XSS surface.
 *
 * <p>The {@code 401} for {@link Outcome#PASSWORD_REQUIRED} is a documented
 * placeholder seam. A bare 401 normally carries {@code WWW-Authenticate}; FR-13
 * replaces this page with a password form (and its own challenge), so no
 * browser credential dialog is offered here.
 *
 * <p>{@code BLOCKED} is intentionally {@code 403}, which reveals that the code
 * exists and was moderated. That is an accepted product decision: the visitor
 * receives feedback that the link was taken down rather than a misleading 404.
 */
@Component
public class LinkStatusPage {

    private static final String REFERRER_POLICY = "no-referrer";
    private static final String ROBOTS_TAG = "noindex";

    /**
     * Status, title and message travel together so adding an outcome is one
     * switch entry rather than three parallel switches.
     */
    private record OutcomeView(HttpStatus status, String title, String message) {
    }

    public ResponseEntity<String> render(Outcome outcome) {
        OutcomeView view = switch (outcome) {
            case NOT_FOUND -> new OutcomeView(HttpStatus.NOT_FOUND,
                    "Link not found",
                    "We couldn't find that short link.");
            case EXPIRED -> new OutcomeView(HttpStatus.GONE,
                    "Link expired",
                    "This short link has expired.");
            case DELETED -> new OutcomeView(HttpStatus.GONE,
                    "Link unavailable",
                    "This short link is no longer available.");
            case DISABLED -> new OutcomeView(HttpStatus.FORBIDDEN,
                    "Link unavailable",
                    "This short link is currently unavailable.");
            case BLOCKED -> new OutcomeView(HttpStatus.FORBIDDEN,
                    "Link unavailable",
                    "This short link is unavailable.");
            case PASSWORD_REQUIRED -> new OutcomeView(HttpStatus.UNAUTHORIZED,
                    "Password required",
                    "This short link is password protected.");
            case ACTIVE -> throw new IllegalArgumentException("ACTIVE links must redirect");
        };

        return page(view);
    }

    /**
     * Contained failure for a legacy or manually seeded row whose stored
     * destination is not a valid URI. Details stay in the log, never the page.
     */
    public ResponseEntity<String> renderInternalError() {
        return page(new OutcomeView(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong",
                "This short link could not be opened."));
    }

    private ResponseEntity<String> page(OutcomeView view) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="robots" content="noindex">
                  <title>%s</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      display: grid;
                      place-items: center;
                      font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                      background: Canvas;
                      color: CanvasText;
                    }
                    main { max-width: 32rem; padding: 2rem; text-align: center; }
                    h1 { margin: 0 0 .75rem; font-size: 1.75rem; }
                    p { margin: 0; line-height: 1.5; }
                  </style>
                </head>
                <body>
                  <main>
                    <h1>%s</h1>
                    <p>%s</p>
                  </main>
                </body>
                </html>
                """.formatted(view.title(), view.title(), view.message());

        return ResponseEntity.status(view.status())
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", REFERRER_POLICY)
                .header("X-Robots-Tag", ROBOTS_TAG)
                .body(html);
    }
}
