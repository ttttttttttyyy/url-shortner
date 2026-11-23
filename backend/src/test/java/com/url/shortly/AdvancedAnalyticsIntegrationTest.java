package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.GeoResolver;
import com.url.shortly.service.PlanTierResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * FR-10 end-to-end: paid owners get breakdowns of the dimensions captured at
 * redirect time; free owners and non-owners are refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_advanced;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=false"
})
class AdvancedAnalyticsIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlanTierResolver tierResolver;

    @MockitoBean
    private GeoResolver geoResolver;

    @BeforeEach
    void setUp() {
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);
        // A stubbed provider stands in for the GeoIP lookup FR-10 leaves open.
        given(geoResolver.countryFor(any())).willAnswer(invocation -> {
            HttpServletRequest request = invocation.getArgument(0);
            return "203.0.113.1".equals(request.getRemoteAddr()) ? "US" : "FR";
        });
    }

    @Test
    @DisplayName("A paid owner sees country/device/OS/browser/referrer breakdowns")
    void paidOwnerSeesBreakdowns() throws Exception {
        String token = register("advanced-owner@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/advanced-target\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");
        Number id = JsonPath.read(body, "$.id");

        visit(code, "203.0.113.1",
                "Mozilla/5.0 (Windows NT 10.0) Chrome/120.0", "https://google.com/search?q=secret");
        visit(code, "203.0.113.2",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Mobile/15E148 Safari/604.1", null);

        MvcResult analytics = mockMvc.perform(get(LINKS + "/" + id + "/analytics/advanced")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(id.longValue()))
                .andExpect(jsonPath("$.countries[?(@.value=='US')].clicks").value(1))
                .andExpect(jsonPath("$.countries[?(@.value=='FR')].clicks").value(1))
                .andExpect(jsonPath("$.devices[?(@.value=='Desktop')].clicks").value(1))
                .andExpect(jsonPath("$.devices[?(@.value=='Mobile')].clicks").value(1))
                .andExpect(jsonPath("$.operatingSystems[?(@.value=='Windows')].clicks").value(1))
                .andExpect(jsonPath("$.operatingSystems[?(@.value=='iOS')].clicks").value(1))
                .andExpect(jsonPath("$.browsers[?(@.value=='Chrome')].clicks").value(1))
                .andExpect(jsonPath("$.browsers[?(@.value=='Safari')].clicks").value(1))
                .andExpect(jsonPath("$.referrers[?(@.value=='google.com')].clicks").value(1))
                .andExpect(jsonPath("$.referrers[?(@.value=='Direct')].clicks").value(1))
                .andReturn();

        // Referrer query strings (which may carry secrets) are never exposed.
        assertThat(analytics.getResponse().getContentAsString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("Free accounts, anonymous callers and non-owners cannot read breakdowns")
    void breakdownsArePaidAndOwnerScoped() throws Exception {
        String paidToken = register("advanced-paid@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + paidToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/scoped-advanced\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        given(tierResolver.tierFor(any())).willReturn(PlanTier.FREE);
        String freeToken = register("advanced-free@example.com");
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);

        mockMvc.perform(get(LINKS + "/" + id + "/analytics/advanced")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + freeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(LINKS + "/" + id + "/analytics/advanced"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(LINKS + "/" + id + "/analytics/advanced")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + paidToken)
                        .param("days", "0"))
                .andExpect(status().isBadRequest());

        String otherToken = register("advanced-other@example.com");
        mockMvc.perform(get(LINKS + "/" + id + "/analytics/advanced")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    private void visit(String code, String clientAddress, String userAgent, String referer)
            throws Exception {
        var builder = get("/" + code)
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .header(HttpHeaders.USER_AGENT, userAgent);
        if (referer != null) {
            builder.header(HttpHeaders.REFERER, referer);
        }
        mockMvc.perform(builder).andExpect(status().isFound());
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
