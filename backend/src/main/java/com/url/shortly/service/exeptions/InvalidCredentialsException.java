package com.url.shortly.service.exeptions;

/**
 * Login failed for any reason. The response is deliberately identical for an
 * unknown email, a wrong password and a disabled account so the endpoint
 * cannot be used to enumerate accounts. Maps to {@code 401 Unauthorized}.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is incorrect.");
    }
}
