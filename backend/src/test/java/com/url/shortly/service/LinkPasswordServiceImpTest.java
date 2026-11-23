package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.ratelimit.LinkPasswordRateLimitGuard;
import com.url.shortly.service.exeptions.IncorrectLinkPasswordException;
import com.url.shortly.service.exeptions.LinkNotProtectedException;
import com.url.shortly.service.exeptions.LinkNotFoundException;
import com.url.shortly.service.exeptions.LinkNotVerifiableException;
import com.url.shortly.service.imp.LinkPasswordServiceImp;

/** FR-13: password verification and link-scoped access tokens. */
@ExtendWith(MockitoExtension.class)
class LinkPasswordServiceImpTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Mock
    private LinkRepository links;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LinkPasswordRateLimitGuard rateLimitGuard;

    private LinkPasswordServiceImp service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                "https://sho.rt", null, null,
                new AppProperties.Security(
                        new AppProperties.Security.Jwt(
                                "shortly", "shortly-web", "secret",
                                Duration.ofMinutes(15), Duration.ofDays(7),
                                Duration.ofMinutes(30)),
                        null, null, null),
                null, null, null);
        service = new LinkPasswordServiceImp(
                links, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC), properties,
                new SecretKeySpec("test-only-secret-at-least-32-bytes-long".getBytes(
                        StandardCharsets.UTF_8), "HmacSHA256"),
                rateLimitGuard);
    }

    @Test
    @DisplayName("Correct password yields a token bound to that link only")
    void verifyIssuesScopedToken() {
        Link link = protectedLink();
        when(links.findByShortCode("secret1")).thenReturn(Optional.of(link));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        LinkPasswordService.VerificationResult result = service.verify("secret1", "correct");

        assertThat(result.token()).isNotBlank();
        assertThat(result.expiresInSeconds()).isEqualTo(1800);
        assertThat(service.tokenValid(result.token(), link)).isTrue();
        assertThat(service.tokenValid(result.token(), otherLink())).isFalse();
        assertThat(service.tokenValid("tampered.token.value", link)).isFalse();
    }

    @Test
    @DisplayName("Wrong password, missing protection and unavailable links are refused")
    void verifyRejectsInvalidCases() {
        Link link = protectedLink();
        when(links.findByShortCode("secret1")).thenReturn(Optional.of(link));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.verify("secret1", "wrong"))
                .isInstanceOf(IncorrectLinkPasswordException.class);

        Link unprotected = protectedLink();
        unprotected.setPasswordHash(null);
        when(links.findByShortCode("plain1")).thenReturn(Optional.of(unprotected));
        assertThatThrownBy(() -> service.verify("plain1", "wrong"))
                .isInstanceOf(LinkNotProtectedException.class);

        Link blocked = protectedLink();
        blocked.setStatus(LinkStatus.BLOCKED);
        when(links.findByShortCode("blocked1")).thenReturn(Optional.of(blocked));
        assertThatThrownBy(() -> service.verify("blocked1", "correct"))
                .isInstanceOf(LinkNotVerifiableException.class);

        when(links.findByShortCode("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify("missing", "correct"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    private static Link protectedLink() {
        Link link = new Link();
        link.setId(7L);
        link.setShortCode("secret1");
        link.setUrl("https://example.com/target");
        link.setStatus(LinkStatus.ACTIVE);
        link.setPasswordHash("$2a$hash");
        return link;
    }

    private static Link otherLink() {
        Link link = new Link();
        link.setId(8L);
        link.setShortCode("secret2");
        return link;
    }
}
