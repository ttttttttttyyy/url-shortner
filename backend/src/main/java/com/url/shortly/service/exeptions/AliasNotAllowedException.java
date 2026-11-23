package com.url.shortly.service.exeptions;

/**
 * Thrown when a caller requests a custom alias but their plan does not include
 * the entitlement (FR-08).
 *
 * <p>Extends {@link InvalidAliasException} so callers that only handle the
 * base type keep working; the HTTP layer maps this subclass to {@code 403}
 * and the base type to {@code 422}. The handler checks the subclass first.
 */
public class AliasNotAllowedException extends InvalidAliasException {

    public AliasNotAllowedException(String message) {
        super(message);
    }
}
