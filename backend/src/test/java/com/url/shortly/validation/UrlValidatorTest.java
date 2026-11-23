package com.url.shortly.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.url.shortly.service.exeptions.InvalidUrlException;

/**
 * Pure unit tests for the destination-URL rules (FR-02): http/https only,
 * host required, no credentials, no self-shortening, bounded length after
 * normalization. No Spring context involved.
 */
class UrlValidatorTest {

    private static final String BASE_URL = "https://sho.rt";

    private final UrlValidator validator = new UrlValidator(BASE_URL);

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            "https://example.com/path?q=1",
            "HTTP://example.com",            // uppercase scheme
            "http://192.168.1.1/x",          // IPv4 literal
            "https://example.com/a//b",      // empty segment: accepted (then normalized)
            "https://example.com/a/./b/../c",// dot segments: accepted (then normalized)
            "https://example.com./x",        // trailing dot is a normal FQDN form
            "https://EXAMPLE.com/x"          // host case does not matter
    })
    void acceptsValidDestinations(String raw) {
        assertThat(validator.validateAndNormalize(raw)).isNotBlank();
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "mailto:user@example.com",
            "tel:+123456789",
            "blob:https://example.com/uuid",
            "about:blank",
            "view-source:https://example.com",
            "//example.com",                 // scheme-relative
            "example.com",                   // no scheme
            "https://",                      // no host
            "https:///path",                 // no host
            "https://exa_mple.com",          // getHost() == null
            "https://example..com",          // empty label -> getHost() == null
            "https://user:pass@example.com",
            "https://@example.com",          // empty credentials
            "https://example.com:0",
            "https://example.com:99999",
            "https://example.com/a b",
            "https://sho.rt/abc",            // self-shortening
            "https://sho.rt./abc",           // self-shortening via FQDN trailing dot
            "https://sho.rt.:443/abc"        // trailing dot + explicit default port
    })
    void rejectsForbiddenDestinations(String raw) {
        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejects a URL that exceeds 2048 characters after encoding")
    void rejectsOverlongUrl() {
        String raw = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> validator.validateAndNormalize(raw))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("at most 2048");
    }

    @Test
    @DisplayName("rejects a null destination")
    void rejectsNull() {
        assertThatThrownBy(() -> validator.validateAndNormalize(null))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("fails fast when the base URL has no host")
    void rejectsRelativeBaseUrl() {
        assertThatThrownBy(() -> new UrlValidator("/api-only-base"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.base-url");
    }

    // -------------------------------------------------------------------------
    // Path policy (accepted product decision, see UrlValidator javadoc):
    // the path is intentionally normalized with URI.normalize(). Dot segments
    // collapse per RFC 3986; empty-segment collapse is Java behavior, not an
    // RFC requirement. These tests pin that policy so a future change is a
    // conscious one.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("path policy: empty segments collapse (signed-URL tradeoff accepted)")
    void normalizesEmptySegments() {
        assertThat(validator.validateAndNormalize("https://example.com/a//b"))
                .isEqualTo("https://example.com/a/b");
    }

    @Test
    @DisplayName("path policy: dot segments collapse")
    void normalizesDotSegments() {
        assertThat(validator.validateAndNormalize("https://example.com/a/./b/../c"))
                .isEqualTo("https://example.com/a/c");
    }

    @Test
    @DisplayName("path policy: the path is percent-encoded but never lowercased")
    void percentEncodesThePath() {
        assertThat(validator.validateAndNormalize("https://example.com/café?q=naïve"))
                .isEqualTo("https://example.com/caf%C3%A9?q=na%C3%AFve");
    }

    // -------------------------------------------------------------------------
    // Self-shortening is an origin check (scheme + host with trailing dots
    // stripped + effective port), so in development http://localhost:3000 is
    // a valid destination even when the shortener runs on
    // http://localhost:10004.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rejects the shortener origin, explicit default port and FQDN trailing dot")
    void rejectsSelfOrigin() {
        assertThatThrownBy(() -> validator.validateAndNormalize("https://sho.rt/abc"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("https://sho.rt:443/abc"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("https://sho.rt./abc"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("https://sho.rt.:443/abc"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("same host on another port is a different origin and is allowed")
    void allowsSameHostOnAnotherPort() {
        UrlValidator devValidator = new UrlValidator("http://localhost:10004");

        assertThat(devValidator.validateAndNormalize("http://localhost:3000/x"))
                .isEqualTo("http://localhost:3000/x");
    }

    @Test
    @DisplayName("the shortener host with a different scheme is a different origin")
    void allowsShortHostOnAnotherScheme() {
        UrlValidator httpValidator = new UrlValidator("https://sho.rt");

        assertThat(httpValidator.validateAndNormalize("http://sho.rt:8080/x"))
                .isEqualTo("http://sho.rt:8080/x");
    }
}
