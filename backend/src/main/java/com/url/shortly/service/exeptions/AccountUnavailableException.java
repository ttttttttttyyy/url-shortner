package com.url.shortly.service.exeptions;

/**
 * The account behind a still-valid access token no longer exists or is not
 * active. Maps to {@code 401} so the client discards its tokens and signs in
 * again.
 */
public class AccountUnavailableException extends RuntimeException {

    public AccountUnavailableException() {
        super("The account is not available.");
    }
}
