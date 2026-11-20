package com.url.shortly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.config.AppProperties;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.LinkResolution.Outcome;
import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.InvalidExpiryException;
import com.url.shortly.service.exeptions.PasswordNotAllowedException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;
import com.url.shortly.service.imp.LinkServiceImp;
import com.url.shortly.validation.UrlValidator;

/**
 * Unit tests for the createLink paths: generated codes, custom aliases,
 * unique-violation translation and expiry validation. No Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkServiceImpTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

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
                null,
                null,
                new AppProperties.Plans(
                        new AppProperties.Plans.Plan(100, false, false, false, false),
                        new AppProperties.Plans.Plan(-1, true, true, true, true)),
                null);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new LinkServiceImp(linkRepository, generator, urlValidator, properties, clock);

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
                        FIXED_NOW.minusSeconds(60)),
                CallerContext.guest()))
                .isInstanceOf(InvalidExpiryException.class)
                .hasMessageContaining("past");
    }

    @Test
    @DisplayName("an expiry beyond the configured maximum is rejected")
    void overlongExpiryIsRejected() {
        Instant farFuture = FIXED_NOW.plusSeconds(3650L * 24 * 3600 + 3600);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, farFuture),
                new CallerContext(1L, PlanTier.FREE)))
                .isInstanceOf(InvalidExpiryException.class)
                .hasMessageContaining("3650");
    }

    @Test
    @DisplayName("guest expiry is capped at the configured retention window")
    void guestExpiryIsCapped() {
        Instant withinWindow = FIXED_NOW.plus(Duration.ofDays(30));
        Instant beyondWindow = FIXED_NOW.plus(Duration.ofDays(31));
        when(generator.generateAndPersist(eq(PlanTier.GUEST), any()))
                .thenAnswer(inv -> persistCaptured(inv.getArgument(1), "guestx1"));

        Link created = service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, withinWindow),
                CallerContext.guest());
        assertThat(created.getExpiresAt()).isEqualTo(withinWindow);

        assertThatThrownBy(() -> service.createLink(
                new CreateLinkRequest("https://example.com/x", null, null, beyondWindow),
                CallerContext.guest()))
                .isInstanceOf(InvalidExpiryException.class)
                .hasMessageContaining("Guest links must be within 30 days");
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

    // ------------------------------------------------------------------
    // FR-03 resolution policy
    // ------------------------------------------------------------------

    private Link resolvedLink(String code) {
        Link link = new Link();
        link.setShortCode(code);
        link.setUrl("https://example.com/target");
        return link;
    }

    private Outcome outcomeFor(Link link) {
        when(linkRepository.findByShortCode(link.getShortCode())).thenReturn(Optional.of(link));
        return service.resolve(link.getShortCode()).outcome();
    }

    @Test
    @DisplayName("an unknown code resolves to NOT_FOUND without a link")
    void unknownCodeIsNotFound() {
        when(linkRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        LinkResolution resolution = service.resolve("missing");

        assertThat(resolution.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(resolution.link()).isNull();
        assertThat(resolution.redirects()).isFalse();
    }

    @Test
    @DisplayName("an ACTIVE link with no expiry and no password redirects")
    void activeLinkRedirects() {
        assertThat(outcomeFor(resolvedLink("active1"))).isEqualTo(Outcome.ACTIVE);
    }

    @Test
    @DisplayName("one second after expiresAt the link is EXPIRED")
    void linkIsExpiredAfterExpiry() {
        Link link = resolvedLink("expired1");
        link.setExpiresAt(FIXED_NOW.minusSeconds(1));

        assertThat(outcomeFor(link)).isEqualTo(Outcome.EXPIRED);
    }

    @Test
    @DisplayName("the exact expiry instant is already expired")
    void expiryBoundaryIsExpired() {
        Link link = resolvedLink("boundary");
        link.setExpiresAt(FIXED_NOW);

        assertThat(outcomeFor(link)).isEqualTo(Outcome.EXPIRED);
    }

    @Test
    @DisplayName("one second before expiresAt the link is still ACTIVE")
    void linkIsActiveBeforeExpiry() {
        Link link = resolvedLink("active2");
        link.setExpiresAt(FIXED_NOW.plusSeconds(1));

        assertThat(outcomeFor(link)).isEqualTo(Outcome.ACTIVE);
    }

    @Test
    @DisplayName("DISABLED, BLOCKED and DELETED statuses resolve to their own outcomes")
    void terminalStatusesAreReported() {
        Link link = resolvedLink("status1");
        link.setStatus(LinkStatus.DISABLED);
        assertThat(outcomeFor(link)).isEqualTo(Outcome.DISABLED);

        link.setStatus(LinkStatus.BLOCKED);
        assertThat(outcomeFor(link)).isEqualTo(Outcome.BLOCKED);

        link.setStatus(LinkStatus.DELETED);
        assertThat(outcomeFor(link)).isEqualTo(Outcome.DELETED);
    }

    @Test
    @DisplayName("a soft-deleted ACTIVE row resolves to DELETED")
    void deletedAtWinsOverActiveStatus() {
        Link link = resolvedLink("deleted2");
        link.setDeletedAt(FIXED_NOW);

        assertThat(outcomeFor(link)).isEqualTo(Outcome.DELETED);
    }

    @Test
    @DisplayName("a password hash resolves to PASSWORD_REQUIRED")
    void passwordHashResolvesToPasswordRequired() {
        Link link = resolvedLink("locked1");
        link.setPasswordHash("{bcrypt}hash");

        assertThat(outcomeFor(link)).isEqualTo(Outcome.PASSWORD_REQUIRED);
    }

    @Test
    @DisplayName("deleted beats blocked and expiry")
    void deletedHasHighestPrecedence() {
        Link link = resolvedLink("deleted3");
        link.setDeletedAt(FIXED_NOW);
        link.setStatus(LinkStatus.BLOCKED);
        link.setExpiresAt(FIXED_NOW.minusSeconds(1));

        assertThat(outcomeFor(link)).isEqualTo(Outcome.DELETED);
    }

    @Test
    @DisplayName("blocked beats disabled, expiry and password")
    void blockedBeatsLaterStates() {
        Link link = resolvedLink("blocked2");
        link.setStatus(LinkStatus.BLOCKED);
        link.setExpiresAt(FIXED_NOW.minusSeconds(1));
        link.setPasswordHash("{bcrypt}hash");

        assertThat(outcomeFor(link)).isEqualTo(Outcome.BLOCKED);
    }

    @Test
    @DisplayName("expiry beats password so an expired link never asks for a secret")
    void expiryBeatsPassword() {
        Link link = resolvedLink("expired2");
        link.setExpiresAt(FIXED_NOW.minusSeconds(1));
        link.setPasswordHash("{bcrypt}hash");

        assertThat(outcomeFor(link)).isEqualTo(Outcome.EXPIRED);
    }
}
