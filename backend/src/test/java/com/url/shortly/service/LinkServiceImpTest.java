package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.config.AppProperties;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.Validation.UrlValidator;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.imp.LinkServiceImp;

/**
 * Unit tests for the createLink paths: generated codes, custom aliases,
 * unique-violation translation and expiry validation. No Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkServiceImpTest {

    @Mock
    private LinkRepository linkRepository;

    @Mock
    private ShortCodeGenerator generator;

    @Mock
    private UrlValidator urlValidator;

    private LinkServiceImp service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                "https://sho.rt",
                null,
                new AppProperties.Link(0, 3650, 30, null),
                null, null, null, null);
        service = new LinkServiceImp(linkRepository, generator, urlValidator, properties);

        when(linkRepository.saveAndFlush(any(Link.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Link persistCaptured(Function<String, Link> persister, String code) {
        return persister.apply(code);
    }

    @Test
    @DisplayName("generated path stores an ACTIVE guest link with no owner")
    void generatedPathStoresGuestLink() {
        when(urlValidator.validateAndNormalize("https://example.com/x"))
                .thenReturn("https://example.com/x");
        when(generator.generateAndPersist(eq(PlanTier.GUEST), any()))
                .thenAnswer(inv -> persistCaptured(inv.getArgument(1), "aB3xY9"));

        Link link = service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, null),
                CallerContext.guest());

        assertThat(link.getShortCode()).isEqualTo("aB3xY9");
        assertThat(link.getStatus()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(link.isCustomAlias()).isFalse();
        assertThat(link.getOwnerId()).isNull();
        assertThat(link.getUrl()).isEqualTo("https://example.com/x");
        assertThat(link.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("alias path validates the alias and stores a custom alias link")
    void aliasPathStoresCustomAlias() {
        when(urlValidator.validateAndNormalize("https://example.com/x"))
                .thenReturn("https://example.com/x");
        doNothing().when(generator).validateCustomAlias("promo", PlanTier.PAID);

        Link link = service.createLink(
                new CreateLinkRequest("https://example.com/x", "promo", null, null),
                new CallerContext(7L, PlanTier.PAID));

        verify(generator).validateCustomAlias("promo", PlanTier.PAID);
        assertThat(link.getShortCode()).isEqualTo("promo");
        assertThat(link.isCustomAlias()).isTrue();
        assertThat(link.getOwnerId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("an alias claimed by another row surfaces as 409 without retry")
    void aliasConflictThrowsConflict() {
        when(urlValidator.validateAndNormalize("https://example.com/x"))
                .thenReturn("https://example.com/x");
        when(linkRepository.saveAndFlush(any(Link.class)))
                .thenThrow(new DataIntegrityViolationException("ux_link_short_code"));
        when(linkRepository.existsByShortCode("promo")).thenReturn(true);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", "promo", null, null),
                new CallerContext(7L, PlanTier.PAID)))
                .isInstanceOf(ShortCodeConflictException.class)
                .hasMessageContaining("promo");

        // Never silently retry with a different alias.
        verify(linkRepository).saveAndFlush(any(Link.class));
    }

    @Test
    @DisplayName("a unique violation on a generated code is translated for the retry loop")
    void generatedCodeCollisionTranslatesToConflict() {
        when(urlValidator.validateAndNormalize("https://example.com/x"))
                .thenReturn("https://example.com/x");
        when(generator.generateAndPersist(eq(PlanTier.GUEST), any()))
                .thenAnswer(inv -> persistCaptured(inv.getArgument(1), "aB3xY9"));
        when(linkRepository.saveAndFlush(any(Link.class)))
                .thenThrow(new DataIntegrityViolationException("ux_link_short_code"));
        when(linkRepository.existsByShortCode("aB3xY9")).thenReturn(true);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, null),
                CallerContext.guest()))
                .isInstanceOf(ShortCodeConflictException.class)
                .hasMessageContaining("aB3xY9");
    }

    @Test
    @DisplayName("a past expiresAt is rejected")
    void pastExpiryIsRejected() {
        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null,
                        Instant.now().minusSeconds(60)),
                CallerContext.guest()))
                .isInstanceOf(InvalidExpiryException.class)
                .hasMessageContaining("past");
    }

    @Test
    @DisplayName("an expiry beyond the configured maximum is rejected")
    void overlongExpiryIsRejected() {
        Instant farFuture = Instant.now().plusSeconds(3650L * 24 * 3600 + 3600);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, farFuture),
                CallerContext.guest()))
                .isInstanceOf(InvalidExpiryException.class)
                .hasMessageContaining("3650");
    }

    @Test
    @DisplayName("a guest or free caller cannot set a password (paid-only entitlement)")
    void passwordRequiresPaidPlan() {
        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, "longenough1", null),
                CallerContext.guest()))
                .isInstanceOf(PasswordNotAllowedException.class)
                .hasMessageContaining("paid plan");
    }

    @Test
    @DisplayName("FR-13 is not implemented: even paid callers cannot set a password yet")
    void passwordNotYetAvailableForPaid() {
        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, "longenough1", null),
                new CallerContext(7L, PlanTier.PAID)))
                .isInstanceOf(PasswordNotAllowedException.class)
                .hasMessageContaining("not available yet");
    }

    @Test
    @DisplayName("a guest requesting an alias is rejected before persistence")
    void guestAliasIsRejected() {
        doThrow(new AliasNotAllowedException("Custom aliases require the paid plan"))
                .when(generator).validateCustomAlias("promo", PlanTier.GUEST);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", "promo", null, null),
                CallerContext.guest()))
                .isInstanceOf(AliasNotAllowedException.class);

        verify(linkRepository, org.mockito.Mockito.never()).saveAndFlush(any(Link.class));
    }
}
