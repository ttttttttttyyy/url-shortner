package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.url.shortly.service.PlanTierResolver;

/**
 * FR-08: paid accounts can claim, change and reuse custom aliases, subject to
 * format, reserved-word and uniqueness rules.
 *
 * <p>No billing provider exists until FR-15, so the tier resolver is stubbed
 * to PAID here. Production still reports {@link PlanTier#FREE} for everyone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_aliases;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=false"
})
class PaidAliasIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlanTierResolver tierResolver;

    @BeforeEach
    void paidTierForIssuedTokens() {
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);
    }

    @Test
    @DisplayName("A paid account creates an alias, sees it as custom and can resolve it publicly")
    void paidUserCreatesAlias() throws Exception {
        String token = register("alias-owner@example.com");

        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/target\",\"alias\":\"My-Alias_1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("My-Alias_1"))
                .andExpect(jsonPath("$.customAlias").value(true))
                .andReturn();

        String shortUrl = JsonPath.read(created.getResponse().getContentAsString(), "$.shortUrl");
        assertThat(shortUrl).isEqualTo("http://localhost:10004/My-Alias_1");

        mockMvc.perform(get("/My-Alias_1"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com/target"));
    }

    @Test
    @DisplayName("Alias format and reserved words are rejected with 422")
    void aliasValidation() throws Exception {
        String token = register("alias-validation@example.com");

        for (String invalid : new String[] { "api", "ADMIN", "bad alias!", "-start", "end_" }) {
            mockMvc.perform(post(LINKS)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://example.com/x\",\"alias\":\"" + invalid + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("Invalid alias"));
        }

        // The request DTO's 32-character ceiling rejects longer aliases before
        // the service; the generator's own length check is covered in its unit tests.
        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\",\"alias\":\""
                                + "a".repeat(33) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    @DisplayName("A duplicate alias is rejected with 409")
    void duplicateAliasIsRejected() throws Exception {
        String token = register("alias-duplicate@example.com");
        createAlias(token, "taken-alias");

        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/other\",\"alias\":\"taken-alias\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Short code already taken"));
    }

    @Test
    @DisplayName("A paid owner can rename an alias; the old code stops resolving")
    void paidOwnerCanRenameAlias() throws Exception {
        String token = register("alias-rename@example.com");
        Long linkId = createGeneratedLink(token);

        mockMvc.perform(patch(LINKS + "/" + linkId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"first-alias\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("first-alias"))
                .andExpect(jsonPath("$.customAlias").value(true));

        mockMvc.perform(patch(LINKS + "/" + linkId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"second_alias\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("second_alias"));

        mockMvc.perform(get("/first-alias"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/second_alias"))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("Renaming onto another link's alias is a 409")
    void aliasRenameConflict() throws Exception {
        String token = register("alias-conflict@example.com");
        createAlias(token, "claimed-alias");
        Long otherLink = createGeneratedLink(token);

        mockMvc.perform(patch(LINKS + "/" + otherLink)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"claimed-alias\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("claimed-alias")));
    }

    @Test
    @DisplayName("The profile reports the resolved paid tier alongside the token claim")
    void profileReportsPaidTier() throws Exception {
        String token = register("alias-profile@example.com");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planTier").value("PAID"));
    }

    private String register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.planTier").value("PAID"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private void createAlias(String token, String alias) throws Exception {
        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\",\"alias\":\"" + alias + "\"}"))
                .andExpect(status().isCreated());
    }

    private Long createGeneratedLink(String token) throws Exception {
        MvcResult result = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/generated\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customAlias").value(false))
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }
}
