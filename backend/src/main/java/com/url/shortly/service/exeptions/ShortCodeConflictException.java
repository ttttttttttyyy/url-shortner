package com.url.shortly.service.exeptions;

import com.url.shortly.service.ShortCodeGenerator;

/**
 * Signals that a candidate short code was rejected by the database unique
 * index.
 *
 * <p>A persister passed to {@link ShortCodeGenerator} throws this exception
 * when the {@code ux_link_short_code} constraint fails, allowing the generator
 * to retry with a fresh candidate.
 */
public class ShortCodeConflictException extends RuntimeException {

    public ShortCodeConflictException(String message) {
        super(message);
    }

    /** Wraps the underlying data-integrity violation for diagnosis. */
    public ShortCodeConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
