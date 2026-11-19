package com.url.shortly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.url.shortly.Repository.AuthSessionRepository;

/**
 * Removes expired refresh sessions after a short forensic grace period.
 *
 * <p>Revoked sessions are retained until their normal expiry so reuse
 * detection can still identify a rotated token. The {@code auth_session} table
 * therefore does not grow without bound.
 */
@Component
public class AuthSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanup.class);
    private static final Duration GRACE_PERIOD = Duration.ofDays(30);

    private final AuthSessionRepository sessions;
    private final Clock clock;

    public AuthSessionCleanup(AuthSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(cron = "0 23 3 * * *")
    @Transactional
    public void purgeExpiredSessions() {
        Instant cutoff = clock.instant().minus(GRACE_PERIOD);
        int deleted = sessions.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} auth sessions expired before {}", deleted, cutoff);
        }
    }
}
