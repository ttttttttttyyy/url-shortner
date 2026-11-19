package com.url.shortly.service.exeptions;

/**
 * Refresh token is missing, unknown, expired or was already rotated. The
 * controller clears the refresh cookie and answers {@code 401}.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("The session is invalid or has expired. Sign in again.");
    }
}
