package com.url.shortly.DTO;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/links}.
 *
 * <p>Annotations cover presence and length only — URL correctness is checked
 * by {@code UrlValidator}, because Hibernate Validator's {@code @URL} accepts
 * schemes such as {@code ftp:} and {@code file:} that must be rejected.
 *
 * <p>{@code password} is reserved for FR-13 (password protection); until that
 * ships, the service explicitly rejects a non-null value and never stores it.
 */
public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @Size(max = 32)             String alias,
        @Size(min = 8, max = 128)   String password,
        Instant expiresAt) {
}
