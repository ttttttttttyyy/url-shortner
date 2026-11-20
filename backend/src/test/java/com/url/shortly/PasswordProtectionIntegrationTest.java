package com.url.shortly;

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
 * FR-13 end-to-end: paid owners protect links, visitors verify a password and
 * receive a link-scoped token that is the only way past the password gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_passwords;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=false"
})
class PasswordProtectionIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlanTierResolver tierResolver;

    @BeforeEach
    void paidTier() {
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);
    }

    @Test
    @DisplayName("A paid owner creates a protected link; the hash is never exposed")
    void protectedLinkCreation() throws Exception {
        String token = register("password-owner@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/protected\","
                                + "\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passwordProtected").doesNotExist())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("correct-horse");

        Number id = JsonPath.read(body, "$.id");
        mockMvc.perform(get(LINKS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordProtected").value(true));
    }

    @Test
    @DisplayName("Wrong passwords fail; the correct one yields a scoped token and cookie")
    void verificationFlow() throws Exception {
        String token = register("password-verify@example.com");
        MvcResult created = createProtectedLink(token, "https://example.com/verify-target");
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(verifyPath(code))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Incorrect password"));

        MvcResult verified = mockMvc.perform(post(verifyPath(code))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        containsString("Path=/" + code)))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andReturn();

        String verifiedBody = verified.getResponse().getContentAsString();
        String accessToken = JsonPath.read(verifiedBody, "$.accessToken");
        String redirectUrl = JsonPath.read(verifiedBody, "$.redirectUrl");
        org.assertj.core.api.Assertions.assertThat(redirectUrl).contains("access=" + accessToken);

        mockMvc.perform(get("/" + code).param("access", accessToken))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("A token for one link cannot unlock a different protected link")
    void tokenIsLinkScoped() throws Exception {
        String token = register("password-scope@example.com");
        String first = JsonPath.read(
                createProtectedLink(token, "https://example.com/one").getResponse().getContentAsString(),
                "$.shortCode");
        String second = JsonPath.read(
                createProtectedLink(token, "https://example.com/two").getResponse().getContentAsString(),
                "$.shortCode");

        String accessToken = JsonPath.read(mockMvc.perform(post(verifyPath(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/" + second).param("access", accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Password can be set and removed later by the owner")
    void passwordCanBeChanged() throws Exception {
        String token = register("password-update@example.com");
        MvcResult created = mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/update-password\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");
        Number id = JsonPath.read(body, "$.id");

        mockMvc.perform(patch(LINKS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordProtected").value(true));
        mockMvc.perform(get("/" + code)).andExpect(status().isUnauthorized());

        mockMvc.perform(patch(LINKS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearPassword\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordProtected").value(false));
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
    }

    @Test
    @DisplayName("Free accounts cannot protect links")
    void freeCannotProtect() throws Exception {
        given(tierResolver.tierFor(any())).willReturn(PlanTier.FREE);
        String token = register("password-free@example.com");
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);

        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/free-password\","
                                + "\"password\":\"correct-horse\"}"))
                .andExpect(status().isForbidden());
    }

    private MvcResult createProtectedLink(String token, String url) throws Exception {
        return mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url
                                + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String verifyPath(String shortCode) {
        return LINKS + "/" + shortCode + "/verify";
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
