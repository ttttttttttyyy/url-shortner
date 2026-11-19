package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.Repository.LinkRepository;

/**
 * FR-04: guest creation burst limits over the full stack.
 *
 * <p>Each test uses its own synthetic remote address so limiter state from one
 * test cannot leak into another; the application context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly-rate-limit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.window=1m",
        "app.security.rate-limit.guest-link-creation=2",
        "app.security.rate-limit.guest-link-creation-daily=50",
        "app.security.rate-limit.global-guest-link-creation=0"
})
class RateLimitIntegrationTest {

    private static final String LINKS = "/api/v1/links";
    private static final String BODY = "{\"url\":\"https://example.com/target\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository linkRepository;

    @BeforeEach
    void clearLinks() {
        linkRepository.deleteAll();
    }

    @Test
    @DisplayName("the third creation from one address is a 429 and persists nothing")
    void burstLimitRejectsThirdRequest() throws Exception {
        String address = "198.51.100.10";

        create(address).andExpect(status().isCreated());
        create(address).andExpect(status().isCreated());
        MvcResult rejected = create(address)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.policy").value("guest-link-creation"))
                .andReturn();

        assertThat(Long.parseLong(rejected.getResponse().getHeader("Retry-After")))
                .isBetween(29L, 30L);
        assertThat(linkRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("malformed bodies consume quota, so they cannot be free CPU burn")
    void malformedBodyConsumesQuota() throws Exception {
        String address = "198.51.100.11";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(LINKS)
                            .with(from(address))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post(LINKS)
                        .with(from(address))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("CORS preflight traffic does not consume guest quota")
    void preflightDoesNotConsumeQuota() throws Exception {
        String address = "198.51.100.12";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(options(LINKS)
                    .with(from(address))
                    .header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "POST"));
        }

        create(address).andExpect(status().isCreated());
        create(address).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("different client addresses have independent buckets")
    void differentAddressesAreIndependent() throws Exception {
        create("198.51.100.13").andExpect(status().isCreated());
        create("198.51.100.14").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("redirects keep working after a guest is rate limited")
    void redirectsAreNotRateLimited() throws Exception {
        String address = "198.51.100.15";

        String firstBody = create(address)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(firstBody, "$.shortCode");

        create(address).andExpect(status().isCreated());
        create(address).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/" + code).with(from(address)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"));
    }

    private ResultActions create(String address) throws Exception {
        return mockMvc.perform(post(LINKS)
                .with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
