package com.url.shortly.service;

import com.url.shortly.Entities.Link;

/** Password verification for protected short links (FR-13). */
public interface LinkPasswordService {

    /** Verifies the password and issues short-lived, link-scoped proof. */
    VerificationResult verify(String shortCode, String rawPassword);

    /** True only for an unexpired token bound to this exact link. */
    boolean tokenValid(String token, Link link);

    record VerificationResult(String token, long expiresInSeconds) {
    }
}
