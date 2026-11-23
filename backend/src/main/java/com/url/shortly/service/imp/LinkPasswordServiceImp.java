package com.url.shortly.service.imp;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.ratelimit.LinkPasswordRateLimitGuard;
import com.url.shortly.service.LinkPasswordService;
import com.url.shortly.service.exeptions.IncorrectLinkPasswordException;
import com.url.shortly.service.exeptions.LinkNotProtectedException;
import com.url.shortly.service.exeptions.LinkNotFoundException;
import com.url.shortly.service.exeptions.LinkNotVerifiableException;

/**
 * FR-13: verifies a link password and issues a bounded, link-scoped token.
 *
 * <p>The token is a signed JWT with a dedicated {@code link-access} audience,
 * so an API access token can never be used to unlock a link. It is bound to the
 * numeric link id and short code, and expires quickly (30 minutes by default).
 */
@Service
public class LinkPasswordServiceImp implements LinkPasswordService {

    static final String LINK_ACCESS_AUDIENCE = "link-access";
    private static final int MAX_PASSWORD_BYTES = 128;
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final LinkRepository links;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AppProperties properties;
    private final LinkPasswordRateLimitGuard rateLimitGuard;
    private final NimbusJwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public LinkPasswordServiceImp(LinkRepository links,
                                  PasswordEncoder passwordEncoder,
                                  Clock clock,
                                  AppProperties properties,
                                  SecretKey jwtSecretKey,
                                  LinkPasswordRateLimitGuard rateLimitGuard) {
        this.links = links;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.properties = properties;
        this.rateLimitGuard = rateLimitGuard;

        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(
                properties.security().jwt().issuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(LINK_ACCESS_AUDIENCE));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        this.decoder = nimbus;
    }

    @Override
    public VerificationResult verify(String shortCode, String rawPassword) {
        Link link = links.findByShortCode(shortCode).orElseThrow(LinkNotFoundException::new);

        if (link.getPasswordHash() == null) {
            throw new LinkNotProtectedException();
        }
        if (link.getStatus() != LinkStatus.ACTIVE || link.getDeletedAt() != null
                || link.isExpired(clock.instant())) {
            throw new LinkNotVerifiableException();
        }

        rateLimitGuard.check(shortCode);

        if (rawPassword == null
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES
                || !passwordEncoder.matches(rawPassword, link.getPasswordHash())) {
            throw new IncorrectLinkPasswordException();
        }

        Instant issuedAt = clock.instant();
        Duration ttl = linkAccessTokenTtl();
        Instant expiresAt = issuedAt.plus(ttl);
        byte[] nonce = new byte[16];
        secureRandom.nextBytes(nonce);

        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(properties.security().jwt().issuer())
                .audience(List.of(LINK_ACCESS_AUDIENCE))
                .subject(link.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("code", link.getShortCode())
                .claim("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                .build();

        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256)
                .build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters
                .from(header, claims)).getTokenValue();

        return new VerificationResult(token, ttl.toSeconds());
    }

    @Override
    public boolean tokenValid(String token, Link link) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        try {
            Jwt jwt = decoder.decode(token);
            return link.getId().toString().equals(jwt.getSubject())
                    && link.getShortCode().equals(jwt.getClaimAsString("code"));
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Duration linkAccessTokenTtl() {
        Duration ttl = properties.security().jwt().linkAccessTokenTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(30) : ttl;
    }
}
