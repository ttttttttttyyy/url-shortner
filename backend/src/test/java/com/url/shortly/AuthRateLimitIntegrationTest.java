package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FR-05 abuse controls.
 *
 * <p>Registration is limited per client address. Login is limited twice: by
 * address (enforced before the body is bound) and by a hash of the normalized
 * email (enforced in the service, so it still applies across many addresses).
 * Every test uses a distinct client address so the shared in-memory buckets
 * cannot leak between test methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_auth_ratelimit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.registration-attempts=2",
        "app.security.rate-limit.login-ip-attempts=3",
        "app.security.rate-limit.login-attempts=2",
        "app.security.rate-limit.window=1m"
})
class AuthRateLimitIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Registration is limited per client address with 429 and Retry-After")
    void registrationIsLimitedPerAddress() throws Exception {
        registerFrom("203.0.113.10", "reg-a@example.com")
                .andExpect(status().isCreated());
        registerFrom("203.0.113.10", "reg-b@example.com")
                .andExpect(status().isCreated());

        registerFrom("203.0.113.10", "reg-c@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, containsString("")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.policy").value("auth-register-ip"));

        // Another address has its own bucket.
        registerFrom("203.0.113.11", "reg-d@example.com")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Login is limited per address even when every email differs")
    void loginIsLimitedPerAddress() throws Exception {
        loginFrom("203.0.113.20", "ip-1@example.com")
                .andExpect(status().isUnauthorized());
        loginFrom("203.0.113.20", "ip-2@example.com")
                .andExpect(status().isUnauthorized());
        loginFrom("203.0.113.20", "ip-3@example.com")
                .andExpect(status().isUnauthorized());

        loginFrom("203.0.113.20", "ip-4@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, containsString("")))
                .andExpect(jsonPath("$.policy").value("auth-login-ip"));

        loginFrom("203.0.113.21", "ip-5@example.com")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Login is limited per account across different addresses")
    void loginIsLimitedPerAccount() throws Exception {
        String email = "account-limit@example.com";

        loginFrom("203.0.113.30", email).andExpect(status().isUnauthorized());
        loginFrom("203.0.113.31", email).andExpect(status().isUnauthorized());
        loginFrom("203.0.113.32", email)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, containsString("")))
                .andExpect(jsonPath("$.policy").value("auth-login-account"));
    }

    @Test
    @DisplayName("Per-address registration and login buckets are independent")
    void registrationAndLoginBucketsAreIndependent() throws Exception {
        String ip = "203.0.113.40";

        registerFrom(ip, "independent-a@example.com").andExpect(status().isCreated());
        registerFrom(ip, "independent-b@example.com").andExpect(status().isCreated());
        registerFrom(ip, "independent-c@example.com").andExpect(status().isTooManyRequests());

        // The login bucket for the same address was untouched.
        loginFrom(ip, "independent-login@example.com")
                .andExpect(status().isUnauthorized());
    }

    private ResultActions registerFrom(String ip, String email) throws Exception {
        return mockMvc.perform(from(post(REGISTER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}"), ip));
    }

    private ResultActions loginFrom(String ip, String email) throws Exception {
        return mockMvc.perform(from(post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"), ip));
    }

    private static MockHttpServletRequestBuilder from(
            MockHttpServletRequestBuilder builder, String clientAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        });
    }
}
