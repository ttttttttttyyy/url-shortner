package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CallerResolver;
import com.url.shortly.Entities.Link;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.enums.PlanTier;

/**
 * Full-context smoke tests against the real application configuration
 * (H2 test profile): guest creation persists a row, the returned code is
 * resolvable, and alias conflicts surface as 409 over the whole stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:shortly-endpoints;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LinkEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository linkRepository;

    /**
     * Replaces the guest-stub resolver so the paid-only alias flows can be
     * exercised end to end. Each test stubs the tier it needs.
     */
    @MockitoBean
    private CallerResolver callerResolver;

    @BeforeEach
    void defaultToGuestCaller() {
        given(callerResolver.current()).willReturn(CallerContext.guest());
    }

    @Test
    @DisplayName("POST /api/v1/links creates a resolvable row and answers 201")
    void linkCreationPersistsRow() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/a/long/path"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.shortCode").isNotEmpty());

        assertThat(linkRepository.count()).isEqualTo(1);
        Link stored = linkRepository.findAll().iterator().next();
        assertThat(stored.getUrl()).isEqualTo("https://example.com/a/long/path");
        assertThat(stored.getOwnerId()).isNull();
        assertThat(stored.isCustomAlias()).isFalse();
    }

    @Test
    @DisplayName("the returned short code resolves through the public redirect endpoint")
    void createdCodeIsResolvable() throws Exception {
        String body = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/target\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"));
    }

    @Test
    @DisplayName("a second request with the same paid alias gets 409")
    void duplicateAliasGetsConflict() throws Exception {
        given(callerResolver.current())
                .willReturn(new CallerContext(7L, PlanTier.PAID));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/first","alias":"promo"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/second","alias":"promo"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a guest requesting an alias gets 403 over the full stack")
    void guestAliasGetsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/x","alias":"promo"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a javascript: URL gets 422 and nothing is persisted")
    void javascriptUrlGetsUnprocessableEntityAndPersistsNothing() throws Exception {
        long rowsBefore = linkRepository.count();

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Validation must happen before saveAndFlush, not after.
        assertThat(linkRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("a file: URL gets 422 and nothing is persisted")
    void fileUrlGetsUnprocessableEntity() throws Exception {
        long rowsBefore = linkRepository.count();

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"file:///etc/passwd\"}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(linkRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("a past expiresAt gets 422, not 500")
    void pastExpiryGetsUnprocessableEntity() throws Exception {
        long rowsBefore = linkRepository.count();

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/x","expiresAt":"2020-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        assertThat(linkRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("Link API endpoints that are not implemented yet stay 501")
    void linkApiIsMounted() throws Exception {
        mockMvc.perform(get("/api/v1/links"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    @DisplayName("A seeded short code redirects through the public endpoint")
    void publicResolutionIsMounted() throws Exception {
        Link link = new Link();
        link.setShortCode("abc1234");
        link.setUrl("https://example.com/");
        linkRepository.save(link);

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/"));
    }
}
