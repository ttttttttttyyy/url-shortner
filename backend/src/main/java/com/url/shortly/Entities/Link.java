package com.url.shortly.Entities;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "link", indexes = {
        @Index(name = "idx_link_owner", columnList = "owner_id"),
        @Index(name = "idx_link_expires_at", columnList = "expires_at")
})
@Getter
@Setter 
@NoArgsConstructor
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 32)
    private String shortCode;

    @Column(name = "destination_url", nullable = false, length = 2048)
    private String url;

    /** Null for guest links. Replace with @ManyToOne User once the User entity exists. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LinkStatus status = LinkStatus.ACTIVE;

    @Column(name = "custom_alias", nullable = false)
    private boolean customAlias;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * FR-03/FR-12: a link is expired as soon as {@code expiresAt} is reached.
     *
     * <p>The caller supplies {@code now} from the application clock so every
     * layer (resolution, dashboard, analytics) agrees on the boundary.
     * {@code !isAfter(now)} makes the boundary itself expired, so a link cannot
     * redirect for one extra instant.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
