package com.url.shortly.service.exeptions;

/**
 * Registration attempted with an email that already has an account.
 * Maps to {@code 409 Conflict}.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("An account with this email already exists.");
    }
}
