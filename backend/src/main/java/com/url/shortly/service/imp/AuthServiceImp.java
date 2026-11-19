package com.url.shortly.service.imp;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.url.shortly.DTO.LoginRequest;
import com.url.shortly.DTO.RegisterRequest;
import com.url.shortly.DTO.UserProfile;
import com.url.shortly.Entities.AppUser;
import com.url.shortly.Entities.AuthSession;
import com.url.shortly.Repository.AppUserRepository;
import com.url.shortly.Repository.AuthSessionRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.UserRole;
import com.url.shortly.enums.UserStatus;
import com.url.shortly.ratelimit.AuthRateLimitGuard;
import com.url.shortly.security.ClientMetadata;
import com.url.shortly.security.EmailNormalizer;
import com.url.shortly.security.JwtService;
import com.url.shortly.security.TokenHasher;
import com.url.shortly.service.AuthResult;
import com.url.shortly.service.AuthService;
import com.url.shortly.service.exeptions.AccountUnavailableException;
import com.url.shortly.service.exeptions.EmailAlreadyRegisteredException;
import com.url.shortly.service.exeptions.InvalidCredentialsException;
import com.url.shortly.service.exeptions.InvalidPasswordException;
import com.url.shortly.service.exeptions.InvalidRefreshTokenException;

/**
 * FR-05 authentication and refresh-session lifecycle.
 *
 * <p>Passwords use the configured bcrypt encoder. Access tokens are
 * short-lived HMAC JWTs; refresh tokens are 256-bit random values stored only
 * as SHA-256 hashes. Each refresh rotates the token. Presenting a token that
 * was already rotated revokes its whole family, which is the standard
 * detection response for a stolen refresh token.
 */
@Service
public class AuthServiceImp implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImp.class);

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final int MAX_REFRESH_TOKEN_LENGTH = 512;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AppUserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final Clock clock;
    private final AuthRateLimitGuard rateLimitGuard;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthServiceImp(AppUserRepository users,
                          AuthSessionRepository sessions,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          AppProperties properties,
                          Clock clock,
                          AuthRateLimitGuard rateLimitGuard) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.clock = clock;
        this.rateLimitGuard = rateLimitGuard;
        // Equalizes the expensive verification path for unknown emails so the
        // login endpoint does not leak account existence through timing.
        this.dummyPasswordHash = passwordEncoder.encode("shortly-invalid-password-placeholder");
    }

    @Override
    @Transactional
    public AuthResult register(RegisterRequest request, ClientMetadata metadata) {
        validatePassword(request.password());
        String email = EmailNormalizer.normalize(request.email());

        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(normalizeDisplayName(request.displayName()));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);

        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race against a concurrent registration for the same email.
            throw new EmailAlreadyRegisteredException();
        }

        log.info("Registered user {}", user.getId());
        return createSession(user, metadata, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public AuthResult login(LoginRequest request, ClientMetadata metadata) {
        String email = EmailNormalizer.normalize(request.email());

        // Per-account bucket, checked before the user lookup. The per-address
        // bucket is enforced by RateLimitInterceptor before body binding.
        rateLimitGuard.checkLoginAccount(email);

        if (request.password() == null
                || request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            passwordEncoder.matches("invalid", dummyPasswordHash);
            throw new InvalidCredentialsException();
        }

        AppUser user = users.findByEmail(email).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                user == null ? dummyPasswordHash : user.getPasswordHash());

        if (user == null || !passwordMatches || !user.isActive()) {
            throw new InvalidCredentialsException();
        }

        user.setLastLoginAt(clock.instant());
        users.save(user);

        log.info("User {} signed in", user.getId());
        return createSession(user, metadata, UUID.randomUUID().toString());
    }

    /**
     * Rotation commits even when the token is rejected, so reuse detection and
     * expiry bookkeeping are not rolled back by the thrown exception.
     */
    @Override
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthResult refresh(String rawRefreshToken, ClientMetadata metadata) {
        String tokenHash = hashRefreshToken(rawRefreshToken);
        Instant now = clock.instant();

        AuthSession session = sessions.findByRefreshTokenHashForUpdate(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (session.isRevoked()) {
            sessions.revokeFamily(session.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}", session.getUserId());
            throw new InvalidRefreshTokenException();
        }

        if (!session.getExpiresAt().isAfter(now)) {
            session.setRevokedAt(now);
            session.setLastUsedAt(now);
            throw new InvalidRefreshTokenException();
        }

        AppUser user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            session.setRevokedAt(now);
            session.setLastUsedAt(now);
            throw new InvalidRefreshTokenException();
        }

        AuthResult result = createSession(user, metadata, session.getFamilyId());

        session.setRevokedAt(now);
        session.setLastUsedAt(now);
        return result;
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()
                || rawRefreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
            return;
        }

        String tokenHash = hashRefreshToken(rawRefreshToken);
        Instant now = clock.instant();
        sessions.findByRefreshTokenHashForUpdate(tokenHash).ifPresent(session -> {
            if (!session.isRevoked()) {
                session.setRevokedAt(now);
                session.setLastUsedAt(now);
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfile profile(Long userId) {
        AppUser user = users.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(AccountUnavailableException::new);
        return UserProfile.from(user);
    }

    private AuthResult createSession(AppUser user, ClientMetadata metadata, String familyId) {
        Instant now = clock.instant();
        String rawRefreshToken = newRefreshToken();

        AuthSession session = new AuthSession();
        session.setUserId(user.getId());
        session.setRefreshTokenHash(TokenHasher.sha256Hex(rawRefreshToken));
        session.setFamilyId(familyId);
        session.setUserAgent(truncate(metadata.userAgent(), MAX_USER_AGENT_LENGTH));
        session.setIpHash(truncate(metadata.ipHash(), 64));
        session.setLastUsedAt(now);
        session.setExpiresAt(now.plus(refreshTokenTtl()));
        sessions.save(session);

        JwtService.IssuedAccessToken accessToken = jwtService.issue(user);
        return new AuthResult(
                accessToken.token(),
                rawRefreshToken,
                accessToken.expiresInSeconds(),
                UserProfile.from(user));
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()
                || rawRefreshToken.length() > MAX_REFRESH_TOKEN_LENGTH) {
            throw new InvalidRefreshTokenException();
        }
        return TokenHasher.sha256Hex(rawRefreshToken);
    }

    private Duration refreshTokenTtl() {
        Duration ttl = properties.security().jwt().refreshTokenTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException(
                    "app.security.jwt.refresh-token-ttl must be positive");
        }
        return ttl;
    }

    private static void validatePassword(String password) {
        if (password == null) {
            throw new InvalidPasswordException("Password is required");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidPasswordException("Password must be at most 72 bytes");
        }
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
