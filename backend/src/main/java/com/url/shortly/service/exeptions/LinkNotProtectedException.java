package com.url.shortly.service.exeptions;

/** Verification was attempted on a link that has no password. Maps to 400. */
public class LinkNotProtectedException extends RuntimeException {

    public LinkNotProtectedException() {
        super("This link is not password protected.");
    }
}
