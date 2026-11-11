package com.url.shortly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Routing contract for the link management API (SoW section 7.2).
 *
 * <p>Every handler is currently a placeholder returning 501, so these tests
 * pin paths, HTTP methods and error handling. When a handler is implemented,
 * replace its {@code isNotImplemented()} assertion with the real status
 * (201 for creation, 200 for reads, 204 for deletion, etc.).
 */
@WebMvcTest(LinkApiController.class)
class LinkApiControllerTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/v1/links accepts a JSON body")
    void createLinkIsNotImplementedYet() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/a/long/path","alias":"promo",
                                 "password":null,"expiresAt":null}
                                """))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("POST /api/v1/links rejects a missing body")
    void createLinkRequiresABody() throws Exception {
        mockMvc.perform(post(LINKS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/links rejects malformed JSON")
    void createLinkRejectsMalformedJson() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/links lists the caller's links")
    void listLinksIsNotImplementedYet() throws Exception {
        mockMvc.perform(get(LINKS))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId} reads one link")
    void getLinkIsNotImplementedYet() throws Exception {
        mockMvc.perform(get(LINKS + "/1"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("PATCH /api/v1/links/{linkId} updates one link")
    void updateLinkIsNotImplementedYet() throws Exception {
        mockMvc.perform(patch(LINKS + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("DELETE /api/v1/links/{linkId} removes or deactivates a link")
    void deleteLinkIsNotImplementedYet() throws Exception {
        mockMvc.perform(delete(LINKS + "/1"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId}/analytics returns analytics")
    void analyticsIsNotImplementedYet() throws Exception {
        mockMvc.perform(get(LINKS + "/1/analytics"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("GET /api/v1/links/{linkId}/qr returns a QR code")
    void qrCodeIsNotImplementedYet() throws Exception {
        mockMvc.perform(get(LINKS + "/1/qr"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("Unsupported HTTP methods are rejected with 405")
    void unsupportedMethodIsRejected() throws Exception {
        mockMvc.perform(put(LINKS))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("A non-numeric linkId is rejected with 400")
    void nonNumericLinkIdIsRejected() throws Exception {
        mockMvc.perform(get(LINKS + "/not-a-number"))
                .andExpect(status().isBadRequest());
    }
}
