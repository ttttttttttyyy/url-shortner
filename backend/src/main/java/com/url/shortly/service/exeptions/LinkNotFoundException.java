package com.url.shortly.service.exeptions;

/**
 * The requested link does not exist or is not owned by the caller.
 *
 * <p>The message is deliberately identical for both cases: distinguishing
 * "someone else's link" from "no such link" would let callers probe which ids
 * exist. Maps to {@code 404 Not Found}.
 */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException() {
        super("Link not found.");
    }
}
