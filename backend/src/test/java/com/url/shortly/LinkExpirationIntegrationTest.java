package com.url.shortly;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;

/**
 * FR-12: owners configure expiry within tier limits, and expired links stop
 * redirecting while remaining manageable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_expiration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=false"
})
class LinkExpirationIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository links;

    @Test
    @DisplayName("A future expiry redirects; once the instant passes, the link is GONE")
    void expiredLinkStopsRedirecting() throws Exception {
        String token = register("expiry-owner@example.com");
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/expires\","
                                + "\"expiresAt\":\"" + expiresAt + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");
        Number id = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound());

        // Simulate the passage of time: the expiry column is the single source.
        Link link = links.findById(id.longValue()).orElseThrow();
        link.setExpiresAt(Instant.now().minusSeconds(1));
        links.saveAndFlush(link);

        mockMvc.perform(get("/" + code))
                .andExpect(status().isGone());

        mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.shortCode=='" + code + "')].effectiveStatus")
                        .value(hasItem("EXPIRED")))
                .andExpect(jsonPath("$.summary.expiredLinks").value(1));
    }

    @Test
    @DisplayName("Clearing the expiry makes the link redirect again")
    void clearingExpiryRestoresRedirect() throws Exception {
        String token = register("expiry-clear@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/clear-expiry\","
                                + "\"expiresAt\":\"" + Instant.now().plus(Duration.ofDays(1))
                                + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.shortCode");
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(patch(LINKS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearExpiry\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("Guests are capped at the retention window; registered users get the full range")
    void expiryLimitsDependOnTier() throws Exception {
        Instant guestWithin = Instant.now().plus(Duration.ofDays(30).minusSeconds(60));
        Instant guestBeyond = Instant.now().plus(Duration.ofDays(31));

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/guest-expiry\","
                                + "\"expiresAt\":\"" + guestWithin + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/guest-expiry-too-long\","
                                + "\"expiresAt\":\"" + guestBeyond + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("30 days")));

        String token = register("expiry-registered@example.com");
        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/long-expiry\","
                                + "\"expiresAt\":\"" + Instant.now().plus(Duration.ofDays(3650))
                                + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Moderation status still wins over an expired timestamp")
    void blockedBeatsExpiry() throws Exception {
        String token = register("expiry-blocked@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/blocked-expired\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.shortCode");
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        Link link = links.findById(id.longValue()).orElseThrow();
        link.setStatus(LinkStatus.BLOCKED);
        link.setExpiresAt(Instant.now().minusSeconds(1));
        links.saveAndFlush(link);

        mockMvc.perform(get("/" + code))
                .andExpect(status().isForbidden());
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }
}
