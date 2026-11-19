package com.url.shortly.Repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.url.shortly.Entities.AuthSession;

import jakarta.persistence.LockModeType;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * Serializes concurrent refreshes and logout for one token. Without this
     * lock, two simultaneous rotations could both observe an active session.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.refreshTokenHash = :hash")
    Optional<AuthSession> findByRefreshTokenHashForUpdate(@Param("hash") String hash);

    /**
     * Revokes every still-active session in a rotation family. Used only when
     * reuse of an already-rotated token is detected.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AuthSession s
               set s.revokedAt = :now, s.lastUsedAt = :now
             where s.familyId = :familyId and s.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Retention cleanup: expired sessions older than the cutoff are removed. */
    @Modifying
    @Query("delete from AuthSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
