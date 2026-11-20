package com.url.shortly.DTO;

/**
 * Short-lived proof of password verification.
 *
 * <p>The frontend can append {@code ?access=<token>} to the public short URL,
 * or use the HttpOnly cookie set by the verify endpoint when API and short-link
 * routes share a host.
 */
public record PasswordVerificationResponse(
        String accessToken,
        String redirectUrl,
        long expiresIn) {
}
