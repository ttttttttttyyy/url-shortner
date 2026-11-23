package com.url.shortly.DTO;

import com.url.shortly.service.AuthResult;

/**
 * Response body for register, login and refresh.
 *
 * <p>The refresh token is intentionally absent: it travels only in the
 * {@code HttpOnly} cookie so JavaScript cannot read it.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserProfile user) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                "Bearer",
                result.expiresInSeconds(),
                result.user());
    }
}
