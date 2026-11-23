package com.url.shortly.service.exeptions;

/**
 * Thrown when a destination URL violates the FR-02 rules (scheme, host,
 * credentials, self-shortening, length).
 *
 * <p>Own type instead of {@code org.springframework.web.util.InvalidUrlException}
 * so the HTTP mapping does not accidentally catch framework-internal URL
 * errors. Maps to {@code 422 Unprocessable Entity}.
 */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}
