package com.url.shortly.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/register}.
 *
 * <p>The password maximum is 72 characters because bcrypt only consumes 72
 * bytes; the service also rejects multi-byte values above that limit. Password
 * strength beyond length is intentionally not enforced here (NIST guidance
 * favours length and blocklists over composition rules).
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Size(max = 80) String displayName) {
}
