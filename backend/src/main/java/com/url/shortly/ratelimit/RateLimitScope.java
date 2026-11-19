package com.url.shortly.ratelimit;

/**
 * Endpoints that share a rate-limit policy family.
 *
 * <p>Link creation is keyed by user id once authenticated. Authentication
 * attempts are always keyed by the client address, even if a valid bearer token
 * happens to accompany the request, because the limit protects the credential
 * endpoint rather than a user's quota.
 */
public enum RateLimitScope {

    LINK_CREATION(true),
    AUTH_REGISTRATION(false),
    AUTH_LOGIN(false);

    private final boolean userKeyed;

    RateLimitScope(boolean userKeyed) {
        this.userKeyed = userKeyed;
    }

    public boolean usesUserKey() {
        return userKeyed;
    }
}
