package com.url.shortly.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Visitor's password for a protected short link (FR-13). */
public record PasswordVerificationRequest(
        @NotBlank @Size(max = 128) String password) {
}
