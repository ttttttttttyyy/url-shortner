package com.url.shortly.service.exeptions;

/**
 * Thrown when a requested {@code expiresAt} is in the past or beyond
 * {@code app.link.max-expiration-days}.
 *
 * <p>Maps to {@code 422 Unprocessable Entity}.
 */
public class InvalidExpiryException extends RuntimeException {

    public InvalidExpiryException(String message) {
        super(message);
    }
}
