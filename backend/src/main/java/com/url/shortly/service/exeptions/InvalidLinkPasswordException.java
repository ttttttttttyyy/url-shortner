package com.url.shortly.service.exeptions;

/** A link password is blank or otherwise unusable. Maps to 400. */
public class InvalidLinkPasswordException extends RuntimeException {

    public InvalidLinkPasswordException(String message) {
        super(message);
    }
}
