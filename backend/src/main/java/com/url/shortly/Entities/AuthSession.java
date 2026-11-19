package com.url.shortly.Entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Server-side refresh session.
 *
 * <p>Only the SHA-256 hash of the opaque refresh token is stored. Tokens in
 * one rotation chain share a {@code familyId}; presenting a token that was
 * already rotated revokes the whole family (reuse detection).
 *
 * <p>The raw client address is never stored: {@code ipHash} is the salted
 * digest produced by {@code ClientIpResolver}.
 */
@Entity
@Table(name = "auth_session",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_auth_session_token", columnNames = "refresh_token_hash"),
        indexes = {
                @Index(name = "idx_auth_session_user", columnList = "user_id, expires_at"),
                @Index(name = "idx_auth_session_expiry", columnList = "expires_at"),
                @Index(name = "idx_auth_session_family", columnList = "family_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        if (lastUsedAt == null) {
            lastUsedAt = now;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
