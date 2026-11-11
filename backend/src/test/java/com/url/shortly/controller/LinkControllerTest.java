package com.url.shortly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SoW FR-03 / FR-12 / FR-13 / FR-14: public link resolution.
 *
 * <p>Until link resolution is implemented, every short code redirects to the
 * placeholder destination. Replace the assertion in
 * {@link #redirectsToPlaceholderDestination()} with the expected real destination
 * once LinkService lookup is wired in.
 */
@WebMvcTest(LinkController.class)
class LinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /{shortCode} answers with 302 and a Location header")
    void redirectsToPlaceholderDestination() throws Exception {
        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/"));
    }

    @Test
    @DisplayName("GET / does not match the short-code route")
    void rootIsNotAShortCode() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isNotFound());
    }
}
