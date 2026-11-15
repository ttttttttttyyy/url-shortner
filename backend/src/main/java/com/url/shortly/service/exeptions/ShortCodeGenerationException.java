package com.url.shortly.service.exeptions;

/**
 * Thrown when no free short code can be found within the configured length and
 * attempt limits.
 *
 * <p>Callers should translate this to a documented {@code 503 Service
 * Unavailable} response (FR-20) and alert operations; it indicates a capacity
 * or configuration problem, not a client error.
 */
public class ShortCodeGenerationException extends RuntimeException {

    public ShortCodeGenerationException(String message) {
        super(message);
    }
}
