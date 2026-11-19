package com.url.shortly.service;

import com.url.shortly.DTO.UserProfile;

/**
 * Internal result of a successful authentication.
 *
 * @param refreshToken raw opaque token; only the controller may place it in a
 *                     cookie and it is never logged
 */
public record AuthResult(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserProfile user) {
}
