package com.url.shortly.service.exeptions;

/**
 * Thrown when a short code does not exist.
 *
 * <p>Callers should translate this to {@code 404 Not Found} (FR-20). A link
 * that exists but is expired, deleted, disabled or blocked is a separate
 * resolution outcome and should not be reported as "not found" without an
 * explicit product decision.
 */
public class InvalidShortCodeException extends RuntimeException {

    public InvalidShortCodeException(String shortCode) {
        super("Short code not found: " + shortCode);
    }
}
