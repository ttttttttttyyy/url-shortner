package com.url.shortly.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.ratelimit.ClientIpResolver;
import com.url.shortly.ratelimit.RateLimitDecision;
import com.url.shortly.ratelimit.RateLimitPolicy;
import com.url.shortly.ratelimit.RateLimitPolicyResolver;
import com.url.shortly.ratelimit.RateLimiter;
import com.url.shortly.service.AdvancedAnalyticsService;
import com.url.shortly.service.ClickAnalyticsService;
import com.url.shortly.service.LinkAdvancedAnalytics;
import com.url.shortly.service.LinkAnalytics;
import com.url.shortly.service.LinkService;
import com.url.shortly.service.OwnedLinks;
import com.url.shortly.service.ShortUrlBuilder;
import com.url.shortly.service.exeptions.AliasNotAllowedException;
import com.url.shortly.service.exeptions.InvalidUrlException;
import com.url.shortly.service.exeptions.ShortCodeConflictException;

/**
 * Routing contract for the link management API (SoW section 7.2).
 *
 * <p>Creation, dashboard, details, update and delete are implemented; the
 * analytics and QR handlers are still placeholders returning 501.
 * When a handler is implemented, replace its {@code isNotImplemented()}
 * assertion with the real status (200 for reads, 204 for deletion, etc.).
 */
@WebMvcTest(LinkApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class LinkApiControllerTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LinkService linkService;

    @MockitoBean
    private CallerResolver callerResolver;

    @MockitoBean
    private ShortUrlBuilder shortUrlBuilder;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private RateLimitPolicyResolver rateLimitPolicyResolver;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private ClickAnalyticsService clickAnalytics;

    @MockitoBean
    private AdvancedAnalyticsService advancedAnalytics;

    @BeforeEach
    void defaultToGuestCallerWithoutRateLimitPolicies() {
        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(rateLimitPolicyResolver.policiesFor(any(), any())).willReturn(List.of());
        given(clock.instant()).willReturn(Instant.parse("2026-11-20T12:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/v1/links answers 429 with Retry-After and policy when limited")
    void createLinkAnswersTooManyRequests() throws Exception {
        given(rateLimitPolicyResolver.policiesFor(any(), any()))
                .willReturn(List.of(new RateLimitPolicy(
                        "guest-link-creation", 5, Duration.ofMinutes(1), false)));
        given(clientIpResolver.key(any())).willReturn("hashed-client");
        given(rateLimiter.tryAcquire(anyString(), any()))
                .willReturn(RateLimitDecision.rejected("guest-link-creation", 12));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.instance").value("/api/v1/links"))
                .andExpect(jsonPath("$.policy").value("guest-link-creation"));

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("A CORS preflight never consults the limiter")
    void preflightDoesNotConsumeQuota() throws Exception {
        clearInvocations(rateLimitPolicyResolver, rateLimiter);

        mockMvc.perform(options(LINKS)
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();

        verify(rateLimitPolicyResolver, never()).policiesFor(any(), any());
        verify(rateLimiter, never()).tryAcquire(any(), any());
    }

    @Test
    @DisplayName("A malformed body is rejected only after the limiter consumed a token")
    void malformedBodyConsumesQuota() throws Exception {
        given(rateLimitPolicyResolver.policiesFor(any(), any()))
                .willReturn(List.of(new RateLimitPolicy(
                        "guest-link-creation", 5, Duration.ofMinutes(1), false)));
        given(clientIpResolver.key(any())).willReturn("hashed-client");
        given(rateLimiter.tryAcquire(anyString(), any()))
                .willReturn(RateLimitDecision.allowed("guest-link-creation"));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verify(rateLimiter).tryAcquire(eq("ip:hashed-client"), any(RateLimitPolicy.class));
    }

    @Test
    @DisplayName("POST /api/v1/links returns 201 with the short URL in Location and body")
    void createLinkReturnsCreatedWithLocation() throws Exception {
        Link link = new Link();
        link.setId(42L);
        link.setShortCode("aB3xY9");
        link.setUrl("https://example.com/a/long/path");
        link.setCustomAlias(false);
        link.setCreatedAt(Instant.parse("2026-11-13T10:15:30Z"));

        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(linkService.createLink(any(CreateLinkRequest.class), any(CallerContext.class)))
                .willReturn(link);
        given(shortUrlBuilder.build("aB3xY9")).willReturn("https://sho.rt/aB3xY9");

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/a/long/path","alias":null,
                                 "password":null,"expiresAt":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "https://sho.rt/aB3xY9"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.shortCode").value("aB3xY9"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/aB3xY9"))
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/a/long/path"))
                .andExpect(jsonPath("$.customAlias").value(false))
                .andExpect(jsonPath("$.createdAt").value("2026-11-13T10:15:30Z"));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects a blank url with 400")
    void createLinkRejectsBlankUrl() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/links rejects a missing body")
    void createLinkRequiresABody() throws Exception {
        mockMvc.perform(post(LINKS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/links rejects malformed JSON")
    void createLinkRejectsMalformedJson() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/links rejects a javascript: URL with 422")
    void createLinkRejectsJavascriptUrl() throws Exception {
        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(linkService.createLink(any(CreateLinkRequest.class), any(CallerContext.class)))
                .willThrow(new InvalidUrlException("Only http and https URLs are allowed"));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"javascript:alert(1)"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Invalid destination URL"));
    }

    @Test
    @DisplayName("POST /api/v1/links answers 409 when an alias is already taken")
    void createLinkConflictsOnTakenAlias() throws Exception {
        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(linkService.createLink(any(CreateLinkRequest.class), any(CallerContext.class)))
                .willThrow(new ShortCodeConflictException("Short code already taken: promo"));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/x","alias":"promo"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Short code already taken"));
    }

    @Test
    @DisplayName("POST /api/v1/links answers 403 when a guest requests an alias")
    void createLinkForbidsGuestAlias() throws Exception {
        given(callerResolver.current()).willReturn(CallerContext.guest());
        given(linkService.createLink(any(CreateLinkRequest.class), any(CallerContext.class)))
                .willThrow(new AliasNotAllowedException("Custom aliases require the paid plan"));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/x","alias":"promo"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Custom aliases require the paid plan"));
    }

    @Test
    @DisplayName("POST /api/v1/links rejects a body without url with 400")
    void createLinkRejectsMissingUrl() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/links lists the caller's links")
    void listLinksReturnsTheDashboard() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        given(linkService.listOwnedLinks(eq(7L), eq(0), eq(20), eq(null)))
                .willReturn(new OwnedLinks(List.of(), 0, 0,
                        new OwnedLinks.UsageSummary(0, 0, 0, 0, 0, 0, 0, 100),
                        Instant.now()));

        mockMvc.perform(get(LINKS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.summary.totalLinks").value(0));

        verify(linkService).listOwnedLinks(7L, 0, 20, null);
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId} reads one owned link")
    void getLinkReturnsDetails() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        given(linkService.getOwnedLink(7L, 1L)).willReturn(persistedLink());
        given(shortUrlBuilder.build("abc123")).willReturn("https://sho.rt/abc123");

        mockMvc.perform(get(LINKS + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.shortCode").value("abc123"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/abc123"))
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.effectiveStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordProtected").value(false));

        verify(linkService).getOwnedLink(7L, 1L);
    }

    @Test
    @DisplayName("PATCH /api/v1/links/{linkId} updates one owned link")
    void updateLinkReturnsUpdatedDetails() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        given(linkService.updateOwnedLink(
                eq(new CallerContext(7L, PlanTier.FREE)), eq(1L), any()))
                .willReturn(persistedLink());
        given(shortUrlBuilder.build("abc123")).willReturn("https://sho.rt/abc123");

        mockMvc.perform(patch(LINKS + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc123"));

        verify(linkService).updateOwnedLink(
                eq(new CallerContext(7L, PlanTier.FREE)), eq(1L), any());
    }

    @Test
    @DisplayName("PATCH /api/v1/links/{linkId} maps a stale update to 409 problem detail")
    void staleUpdateReturnsConflict() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        given(linkService.updateOwnedLink(
                eq(new CallerContext(7L, PlanTier.FREE)), eq(1L), any()))
                .willThrow(new OptimisticLockingFailureException("stale version"));

        mockMvc.perform(patch(LINKS + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Concurrent modification"))
                .andExpect(jsonPath("$.instance").value("/api/v1/links/1"));
    }

    @Test
    @DisplayName("DELETE /api/v1/links/{linkId} soft-deletes an owned link")
    void deleteLinkReturnsNoContent() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));

        mockMvc.perform(delete(LINKS + "/1"))
                .andExpect(status().isNoContent());

        verify(linkService).deleteOwnedLink(7L, 1L);
    }

    private static Link persistedLink() {
        Link link = new Link();
        link.setId(1L);
        link.setShortCode("abc123");
        link.setUrl("https://example.com/page");
        return link;
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId}/analytics returns basic analytics")
    void analyticsReturnsData() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        given(linkService.getOwnedLink(7L, 1L)).willReturn(persistedLink());
        given(clickAnalytics.analytics(1L, 7)).willReturn(new LinkAnalytics(
                1L, 5, 2, 7, 86400,
                List.of(new LinkAnalytics.DailyClicks(
                        Instant.parse("2026-11-20T00:00:00Z"), 5, 2))));

        mockMvc.perform(get(LINKS + "/1/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(1))
                .andExpect(jsonPath("$.totalClicks").value(5))
                .andExpect(jsonPath("$.uniqueClicks").value(2));

        verify(clickAnalytics).analytics(1L, 7);
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId}/analytics/advanced is paid-only")
    void advancedAnalyticsIsPaidOnly() throws Exception {
        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.FREE));
        mockMvc.perform(get(LINKS + "/1/analytics/advanced"))
                .andExpect(status().isForbidden());

        given(callerResolver.current()).willReturn(new CallerContext(7L, PlanTier.PAID));
        given(linkService.getOwnedLink(7L, 1L)).willReturn(persistedLink());
        given(advancedAnalytics.analytics(1L, 7)).willReturn(new LinkAdvancedAnalytics(
                1L, 7,
                List.of(new LinkAdvancedAnalytics.Breakdown("US", 3)),
                List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get(LINKS + "/1/analytics/advanced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries[0].value").value("US"))
                .andExpect(jsonPath("$.countries[0].clicks").value(3));
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId}/qr returns a QR code")
    void qrCodeIsNotImplementedYet() throws Exception {
        mockMvc.perform(get(LINKS + "/1/qr"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("Unsupported HTTP methods are rejected with 405")
    void unsupportedMethodIsRejected() throws Exception {
        mockMvc.perform(put(LINKS))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("A non-numeric linkId is rejected with 400")
    void nonNumericLinkIdIsRejected() throws Exception {
        mockMvc.perform(get(LINKS + "/not-a-number"))
                .andExpect(status().isBadRequest());
    }
}
