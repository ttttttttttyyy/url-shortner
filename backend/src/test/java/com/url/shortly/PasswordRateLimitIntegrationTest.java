package com.url.shortly;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.PlanTierResolver;

/** FR-13: password guessing is throttled per address and per link. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_password_limits;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.password-verification=2",
        "app.security.rate-limit.registration-attempts=1000",
        "app.security.rate-limit.login-attempts=1000"
})
class PasswordRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlanTierResolver tierResolver;

    @Test
    @DisplayName("A third password attempt is throttled with 429 and Retry-After")
    void passwordAttemptsAreLimited() throws Exception {
        given(tierResolver.tierFor(any())).willReturn(PlanTier.PAID);
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"password-limit@example.com\","
                                + "\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(
                registered.getResponse().getContentAsString(), "$.accessToken");

        MvcResult created = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/limited\","
                                + "\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(created.getResponse().getContentAsString(), "$.shortCode");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/links/" + code + "/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/links/" + code + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, containsString("")))
                .andExpect(jsonPath("$.policy").value("link-password-verify"));
    }
}
