package com.url.shortly.service.exeptions;

/** Wrong password supplied at the verification endpoint. Maps to 401. */
public class IncorrectLinkPasswordException extends RuntimeException {

    public IncorrectLinkPasswordException() {
        super("Incorrect password.");
    }
}
