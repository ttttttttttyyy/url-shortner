package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.url.shortly.Repository.LinkRepository;

/**
 * FR-04: sustained-abuse cap.
 *
 * <p>The burst policy is set high so only the 24h policy can reject. The
 * winner must be the policy with the longest wait, and the wait must reflect
 * a 24-hour refill rather than one minute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly-rate-limit-daily;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.window=1m",
        "app.security.rate-limit.guest-link-creation=100",
        "app.security.rate-limit.guest-link-creation-daily=2",
        "app.security.rate-limit.global-guest-link-creation=0"
})
class DailyRateLimitIntegrationTest {

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
    @DisplayName("the daily policy wins with a multi-hour Retry-After")
    void dailyLimitProducesTheLongestRetryAfter() throws Exception {
        String address = "198.51.100.20";

        create(address).andExpect(status().isCreated());
        create(address).andExpect(status().isCreated());

        MvcResult rejected = create(address)
                .andExpect(status().isTooManyRequests())
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(3_600L, 43_200L);
        assertThat(rejected.getResponse().getContentAsString())
                .contains("\"policy\":\"guest-link-creation-daily\"");
        assertThat(linkRepository.count()).isEqualTo(2);
    }

    private ResultActions create(String address) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
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
