package com.url.shortly;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-context smoke test: verifies that both controllers are discovered and
 * mounted against the real application configuration (H2 test profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Public resolution endpoint is mounted")
    void publicResolutionIsMounted() throws Exception {
        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("Link API endpoints are mounted")
    void linkApiIsMounted() throws Exception {
        mockMvc.perform(get("/api/v1/links"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("Link creation accepts JSON over the full stack")
    void linkCreationAcceptsJson() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isNotImplemented());
    }
}
