package com.url.shortly.service.exeptions;

/**
 * Thrown when a caller requests password protection they cannot have: the
 * SoW feature matrix makes it a paid-only entitlement (FR-13), and until
 * FR-13 ships even paid callers are refused rather than storing plaintext.
 *
 * <p>Maps to {@code 403 Forbidden}.
 */
public class PasswordNotAllowedException extends RuntimeException {

    public PasswordNotAllowedException(String message) {
        super(message);
    }
}
