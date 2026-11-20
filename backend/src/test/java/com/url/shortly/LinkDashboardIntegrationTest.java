package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

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
 * FR-06: the dashboard reads only the authenticated owner's links and reports
 * account-wide counts that are independent of paging and filtering.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_dashboard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.registration-attempts=1000"
})
class LinkDashboardIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository links;

    @Test
    @DisplayName("The dashboard lists only the caller's non-deleted links with account-wide counts")
    void dashboardIsOwnershipScoped() throws Exception {
        Registered owner = register("dashboard-owner@example.com");
        Registered other = register("dashboard-other@example.com");

        seed("dashown1", owner.id(), LinkStatus.ACTIVE, null, false);
        seed("dashown2", owner.id(), LinkStatus.ACTIVE, null, true);
        seed("dashown3", owner.id(), LinkStatus.DISABLED, null, false);
        seed("dashown4", owner.id(), LinkStatus.ACTIVE,
                Instant.now().minusSeconds(60), false);
        seed("dashdel1", owner.id(), LinkStatus.DELETED, null, false);
        seed("dashoth1", other.id(), LinkStatus.ACTIVE, null, false);
        seed("dashguest", null, LinkStatus.ACTIVE, null, false);

        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashown1')].destinationUrl")
                        .value(hasItem("https://example.com/dashown1")))
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashown1')].shortUrl")
                        .value(hasItem(containsString("http://localhost:10004/dashown1"))))
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashown1')].status")
                        .value(hasItem("ACTIVE")))
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashdel1')]").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashoth1')]").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.shortCode == 'dashguest')]").doesNotExist())
                .andExpect(jsonPath("$.summary.totalLinks").value(4))
                .andExpect(jsonPath("$.summary.activeLinks").value(2))
                .andExpect(jsonPath("$.summary.disabledLinks").value(1))
                .andExpect(jsonPath("$.summary.expiredLinks").value(1))
                .andExpect(jsonPath("$.summary.customAliases").value(1))
                .andExpect(jsonPath("$.summary.maxLinks").value(100));
    }

    @Test
    @DisplayName("Paging and status filtering do not change the account-wide summary")
    void pagingAndFiltering() throws Exception {
        Registered owner = register("paging-owner@example.com");
        for (int i = 0; i < 5; i++) {
            seed("pageactive" + i, owner.id(), LinkStatus.ACTIVE, null, false);
        }
        for (int i = 0; i < 2; i++) {
            seed("pagedisabled" + i, owner.id(), LinkStatus.DISABLED, null, false);
        }

        MvcResult firstPage = mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.summary.totalLinks").value(7))
                .andReturn();

        MvcResult secondPage = mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();

        List<String> firstCodes = JsonPath.read(
                firstPage.getResponse().getContentAsString(), "$.items[*].shortCode");
        List<String> secondCodes = JsonPath.read(
                secondPage.getResponse().getContentAsString(), "$.items[*].shortCode");
        assertThat(firstCodes).doesNotContainAnyElementsOf(secondCodes);

        mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].status")
                        .value(everyItem(equalTo("DISABLED"))))
                // Still seven links in the account, not just the two shown.
                .andExpect(jsonPath("$.summary.totalLinks").value(7));
    }

    @Test
    @DisplayName("Invalid paging parameters and a deleted-link filter are rejected")
    void invalidParametersAreRejected() throws Exception {
        Registered owner = register("params-owner@example.com");

        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("status", "DELETED"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("The dashboard needs a valid bearer token")
    void dashboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get(LINKS))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer tampered"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A client-supplied owner id cannot widen the result")
    void ownerIdParameterIsIgnored() throws Exception {
        Registered owner = register("spoof-owner@example.com");
        Registered other = register("spoof-other@example.com");
        seed("spoofown1", owner.id(), LinkStatus.ACTIVE, null, false);
        seed("spoofoth1", other.id(), LinkStatus.ACTIVE, null, false);

        mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("ownerId", other.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].shortCode").value("spoofown1"));
    }

    @Test
    @DisplayName("A new account sees an empty dashboard with zeroed counts")
    void emptyDashboard() throws Exception {
        Registered owner = register("empty-dashboard@example.com");

        mockMvc.perform(get(LINKS).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.summary.totalLinks").value(0))
                .andExpect(jsonPath("$.summary.activeLinks").value(0))
                .andExpect(jsonPath("$.summary.maxLinks").value(100));
    }

    private Registered register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        Number id = JsonPath.read(body, "$.user.id");
        return new Registered(id.longValue(), token);
    }

    private Link seed(String code, Long ownerId, LinkStatus status,
                      Instant expiresAt, boolean customAlias) {
        Link link = new Link();
        link.setShortCode(code);
        link.setUrl("https://example.com/" + code);
        link.setOwnerId(ownerId);
        link.setStatus(status);
        link.setExpiresAt(expiresAt);
        link.setCustomAlias(customAlias);
        return links.saveAndFlush(link);
    }

    private record Registered(Long id, String token) {
    }
}
