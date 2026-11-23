package com.url.shortly.service.exeptions;

/**
 * Thrown when a requested custom alias fails entitlement, format, length or
 * reserved-word validation (FR-08).
 *
 * <p>Callers should translate this to {@code 403 Forbidden} when the plan does
 * not include custom aliases and {@code 422 Unprocessable Entity} for an
 * invalid or reserved alias (FR-20). Uniqueness is enforced by the database
 * unique index and should surface as {@code 409 Conflict}.
 */
public class InvalidAliasException extends RuntimeException {

    public InvalidAliasException(String message) {
        super(message);
    }
}
