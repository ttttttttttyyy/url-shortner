package com.url.shortly.service.exeptions;

/**
 * Thrown when an owner tries to change the status of a link that is managed by
 * an administrator (currently {@code BLOCKED}).
 */
public class StatusChangeNotAllowedException extends RuntimeException {

    public StatusChangeNotAllowedException() {
        super("A blocked link can only be changed by an administrator");
    }
}
