package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;

/**
 * FR-09 end-to-end: eligible redirects are recorded, and only the owner can
 * read the basic analytics for their link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_analytics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=false"
})
class ClickAnalyticsIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository links;

    @Test
    @DisplayName("Eligible redirects record clicks and the owner sees totals, uniques and a trend")
    void analyticsReflectsEligibleVisits() throws Exception {
        Registered owner = register("analytics-owner@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/analytics-target\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");
        Number id = JsonPath.read(body, "$.id");

        visit(code, "203.0.113.1");
        visit(code, "203.0.113.1");
        visit(code, "203.0.113.2");

        MvcResult analytics = mockMvc.perform(get(LINKS + "/" + id + "/analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(id.longValue()))
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.uniqueClicks").value(2))
                .andExpect(jsonPath("$.uniqueWindowSeconds").value(86400))
                .andExpect(jsonPath("$.rangeDays").value(7))
                .andExpect(jsonPath("$.series.length()").value(8))
                .andReturn();

        int lastBucket = JsonPath.read(
                analytics.getResponse().getContentAsString(), "$.series[7].clicks");
        assertThat(lastBucket).isEqualTo(3);
    }

    @Test
    @DisplayName("Non-redirecting links do not record clicks")
    void disabledLinksAreNotRecorded() throws Exception {
        Registered owner = register("analytics-disabled@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/disabled-target\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.shortCode");
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        Link link = links.findById(id.longValue()).orElseThrow();
        link.setStatus(LinkStatus.DISABLED);
        links.saveAndFlush(link);

        // A disabled link renders the status page instead of redirecting, so
        // no click is recorded.
        mockMvc.perform(from(get("/" + code), "203.0.113.3"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(LINKS + "/" + id + "/analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.uniqueClicks").value(0));
    }

    @Test
    @DisplayName("Analytics requires ownership and a valid reporting range")
    void analyticsIsOwnerScopedAndValidated() throws Exception {
        Registered owner = register("analytics-scope-owner@example.com");
        Registered other = register("analytics-scope-other@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/scoped-target\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(LINKS + "/" + id + "/analytics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LINKS + "/" + id + "/analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(LINKS + "/" + id + "/analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("days", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(LINKS + "/" + id + "/analytics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .param("days", "366"))
                .andExpect(status().isBadRequest());
    }

    private void visit(String code, String clientAddress) throws Exception {
        mockMvc.perform(from(get("/" + code), clientAddress))
                .andExpect(status().isFound());
    }

    private Registered register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.user.id");
        return new Registered(id.longValue(), JsonPath.read(body, "$.accessToken"));
    }

    private static MockHttpServletRequestBuilder from(
            MockHttpServletRequestBuilder builder, String clientAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        });
    }

    private record Registered(Long id, String token) {
    }
}
