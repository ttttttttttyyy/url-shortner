package com.url.shortly.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.url.shortly.Entities.AppUser;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;

/**
 * Issues short-lived HMAC-signed access tokens.
 *
 * <p>The subject is the numeric user id. Role and tier are included as claims
 * so request authorization does not need a database round trip. Changes to
 * either therefore take effect when the current access token expires
 * (15 minutes by default); {@code GET /auth/me} still reads the database so a
 * disabled account cannot keep using the profile endpoint.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final AppProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(AppUser user) {
        AppProperties.Security.Jwt config = properties.security().jwt();
        Duration ttl = config.accessTokenTtl();
        if (ttl == null || ttl.toSeconds() < 1) {
            throw new IllegalStateException(
                    "app.security.jwt.access-token-ttl must be at least one second");
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.issuer())
                .audience(List.of(config.audience()))
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                // FR-15 will resolve the effective subscription; until then
                // every registered account is on the free tier.
                .claim("tier", PlanTier.FREE.name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedAccessToken(token, expiresAt, ttl.toSeconds());
    }

    public record IssuedAccessToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
