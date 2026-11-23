package com.url.shortly.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.Entities.Link;
import com.url.shortly.ratelimit.ClientIpResolver;
import com.url.shortly.ratelimit.RateLimitPolicyResolver;
import com.url.shortly.ratelimit.RateLimiter;
import com.url.shortly.service.ClickAnalyticsService;
import com.url.shortly.service.GeoResolver;
import com.url.shortly.service.LinkPasswordService;
import com.url.shortly.service.LinkResolution;
import com.url.shortly.service.LinkResolution.Outcome;
import com.url.shortly.service.LinkService;

/**
 * SoW FR-03: controller-slice tests for the redirect/status-page decision.
 *
 * <p>The service is mocked so every outcome can be exercised without a
 * database. Route-guard tests verify that non-code paths never reach the
 * service at all.
 */
@WebMvcTest(LinkController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(LinkStatusPage.class)
class LinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LinkService linkService;

    @MockitoBean
    private CallerResolver callerResolver;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private ClickAnalyticsService clickAnalytics;

    @MockitoBean
    private GeoResolver geoResolver;

    @MockitoBean
    private LinkPasswordService linkPasswords;

    @MockitoBean
    private RateLimitPolicyResolver rateLimitPolicyResolver;

    @MockitoBean
    private RateLimiter rateLimiter;

    private static Link link(String url) {
        Link link = new Link();
        link.setShortCode("abc1234");
        link.setUrl(url);
        return link;
    }

    private static LinkResolution resolution(Outcome outcome) {
        Link link = link("https://example.com/target");
        return outcome == Outcome.NOT_FOUND
                ? LinkResolution.notFound()
                : new LinkResolution(link, outcome);
    }

    @Test
    @DisplayName("an ACTIVE link answers 302 with Location and safety headers")
    void redirectsActiveLink() throws Exception {
        given(linkService.resolve("abc1234"))
                .willReturn(new LinkResolution(
                        link("https://example.com/target?q=1"), Outcome.ACTIVE));

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target?q=1"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("an unknown code shows the 404 page with no Location")
    void notFoundShowsStatusPage() throws Exception {
        given(linkService.resolve("missing")).willReturn(resolution(Outcome.NOT_FOUND));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("We couldn't find that short link.")));
    }

    @Test
    @DisplayName("an expired link shows 410 and never redirects")
    void expiredShowsGone() throws Exception {
        given(linkService.resolve("expired1")).willReturn(resolution(Outcome.EXPIRED));

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("This short link has expired.")));
    }

    @Test
    @DisplayName("a deleted link shows 410 and never redirects")
    void deletedShowsGone() throws Exception {
        given(linkService.resolve("deleted1")).willReturn(resolution(Outcome.DELETED));

        mockMvc.perform(get("/deleted1"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("no longer available")));
    }

    @Test
    @DisplayName("a disabled link shows 403 and never redirects")
    void disabledShowsForbidden() throws Exception {
        given(linkService.resolve("disabled1")).willReturn(resolution(Outcome.DISABLED));

        mockMvc.perform(get("/disabled1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("currently unavailable")));
    }

    @Test
    @DisplayName("a blocked link shows 403 without revealing moderation details")
    void blockedShowsForbiddenWithoutDetail() throws Exception {
        given(linkService.resolve("blocked1")).willReturn(resolution(Outcome.BLOCKED));

        mockMvc.perform(get("/blocked1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("This short link is unavailable.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("moderat"))));
    }

    @Test
    @DisplayName("a password-protected link shows the 401 placeholder page")
    void passwordRequiredShowsPlaceholder() throws Exception {
        given(linkService.resolve("locked1")).willReturn(resolution(Outcome.PASSWORD_REQUIRED));

        mockMvc.perform(get("/locked1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(containsString("password protected")));
    }

    @Test
    @DisplayName("a malformed stored destination is contained as a generic 500")
    void malformedStoredDestinationIsContained() throws Exception {
        given(linkService.resolve("legacy1"))
                .willReturn(new LinkResolution(link("https://example.com/ bad"), Outcome.ACTIVE));

        mockMvc.perform(get("/legacy1"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("could not be opened")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("https://example.com"))));
    }

    @Test
    @DisplayName("static-looking paths never reach the resolution service")
    void staticPathsDoNotQueryTheDatabase() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("codes longer than 32 characters are rejected by the route")
    void overlongCodeDoesNotQueryTheDatabase() throws Exception {
        mockMvc.perform(get("/" + "a".repeat(33)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("a trailing slash is not a valid short code")
    void trailingSlashIsNotAShortCode() throws Exception {
        mockMvc.perform(get("/abc1234/"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(linkService);
    }

    @Test
    @DisplayName("GET / does not match the short-code route")
    void rootIsNotAShortCode() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(linkService);
    }
}
