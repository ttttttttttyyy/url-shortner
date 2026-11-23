package com.url.shortly.service.exeptions;

/**
 * An update was empty or internally inconsistent (blank url, mutually
 * exclusive expiry fields). Maps to {@code 422 Unprocessable Entity}.
 */
public class InvalidLinkUpdateException extends RuntimeException {

    public InvalidLinkUpdateException() {
        super("Provide at least one editable field: url, expiresAt, clearExpiry or active.");
    }

    public InvalidLinkUpdateException(String detail) {
        super(detail);
    }
}
