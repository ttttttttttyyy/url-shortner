package com.url.shortly.service.exeptions;

/**
 * Password violates a rule that bean validation cannot express, such as the
 * bcrypt 72-byte input limit. Maps to {@code 400 Bad Request}.
 */
public class InvalidPasswordException extends RuntimeException {

    public InvalidPasswordException(String message) {
        super(message);
    }
}
