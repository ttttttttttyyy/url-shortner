package com.url.shortly.service.exeptions;

/** A protected link is not currently eligible for verification. Maps to 422. */
public class LinkNotVerifiableException extends RuntimeException {

    public LinkNotVerifiableException() {
        super("This link is not available for password verification.");
    }
}
