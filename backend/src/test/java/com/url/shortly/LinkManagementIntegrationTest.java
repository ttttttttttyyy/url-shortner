package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;
import com.url.shortly.Repository.LinkRepository;

/**
 * FR-07: per-link read, update and soft delete, all scoped to the owner in the
 * access token. A link belonging to someone else is indistinguishable from a
 * missing link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_management;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.registration-attempts=1000"
})
class LinkManagementIntegrationTest {

    private static final String LINKS = "/api/v1/links";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LinkRepository links;

    @Test
    @DisplayName("An owner can read their link; other users, guests and unknown ids get 404")
    void readIsOwnershipScoped() throws Exception {
        Registered owner = register("manage-read-owner@example.com");
        Registered other = register("manage-read-other@example.com");
        Link owned = seed("readown1", owner.id(), null);
        Link foreign = seed("readoth1", other.id(), null);
        Link guest = seed("readguest", null, null);

        mockMvc.perform(get(LINKS + "/" + owned.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(owned.getId()))
                .andExpect(jsonPath("$.shortCode").value("readown1"))
                .andExpect(jsonPath("$.shortUrl").value(containsString("http://localhost:10004/readown1")))
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/readown1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordProtected").value(false));

        for (Long hiddenId : new Long[] { foreign.getId(), guest.getId(), 999_999L }) {
            mockMvc.perform(get(LINKS + "/" + hiddenId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Link not found"));
        }
    }

    @Test
    @DisplayName("Link management requires an authenticated owner")
    void managementRequiresAuthentication() throws Exception {
        mockMvc.perform(get(LINKS + "/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(LINKS + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(LINKS + "/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An owner can change URL, expiry and the active switch without changing the code")
    void ownerCanUpdatePermittedProperties() throws Exception {
        Registered owner = register("manage-update@example.com");
        Link link = seed("update1", owner.id(), null);
        Instant future = Instant.now().plus(Duration.ofDays(10));

        mockMvc.perform(patch(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://new.example.com/path\","
                                + "\"active\":false,"
                                + "\"expiresAt\":\"" + future + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("update1"))
                .andExpect(jsonPath("$.destinationUrl").value("https://new.example.com/path"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        Link updated = links.findById(link.getId()).orElseThrow();
        assertThat(updated.getUrl()).isEqualTo("https://new.example.com/path");
        assertThat(updated.getStatus()).isEqualTo(LinkStatus.DISABLED);
        assertThat(updated.getExpiresAt()).isCloseTo(future, within(1, ChronoUnit.MILLIS));
        assertThat(updated.getShortCode()).isEqualTo("update1");

        // Re-enabling does not alter the other fields.
        mockMvc.perform(patch(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.destinationUrl").value("https://new.example.com/path"));
    }

    @Test
    @DisplayName("Unsupported or invalid updates are rejected")
    void invalidUpdatesAreRejected() throws Exception {
        Registered owner = register("manage-invalid@example.com");
        Link link = seed("invalid1", owner.id(), null);
        String url = LINKS + "/" + link.getId();
        String bearer = "Bearer " + owner.token();

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"custom\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secretpass\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Invalid link update"));

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com/x\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("A non-owner cannot update or delete someone else's link")
    void nonOwnerCannotModifyLink() throws Exception {
        Registered owner = register("manage-protected@example.com");
        Registered other = register("manage-attacker@example.com");
        Link link = seed("protected1", owner.id(), null);

        mockMvc.perform(patch(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isNotFound());

        assertThat(links.findById(link.getId()).orElseThrow().getStatus())
                .isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    @DisplayName("Delete is a reversible soft delete and is idempotent")
    void deleteSoftDeletesLink() throws Exception {
        Registered owner = register("manage-delete@example.com");
        Link link = seed("delete1", owner.id(), null);

        mockMvc.perform(delete(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        Link deleted = links.findById(link.getId()).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(LinkStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getShortCode()).isEqualTo("delete1");

        mockMvc.perform(get(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalLinks").value(0));

        // A second delete is a no-op, not a 404.
        mockMvc.perform(delete(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("An owner cannot re-enable a link blocked by moderation")
    void blockedLinkCannotBeReactivated() throws Exception {
        Registered owner = register("manage-blocked@example.com");
        Link link = seed("blocked1", owner.id(), null);
        link.setStatus(LinkStatus.BLOCKED);
        links.saveAndFlush(link);

        mockMvc.perform(patch(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Status change not allowed"))
                .andExpect(jsonPath("$.instance").value("/api/v1/links/" + link.getId()));

        assertThat(links.findById(link.getId()).orElseThrow().getStatus())
                .isEqualTo(LinkStatus.BLOCKED);
        mockMvc.perform(get("/blocked1")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A row with deletedAt set is hidden from management like a DELETED row")
    void deletedAtOnlyRowIsHidden() throws Exception {
        Registered owner = register("manage-deletedat@example.com");
        Link link = seed("deletedat1", owner.id(), null);
        link.setDeletedAt(Instant.now());
        links.saveAndFlush(link);

        String url = LINKS + "/" + link.getId();
        String bearer = "Bearer " + owner.token();

        mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound());
        // Treated as already deleted: a repeat delete is still a no-op.
        mockMvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());
        // The DB rounds timestamps to microseconds; allow a small tolerance.
        assertThat(links.findById(link.getId()).orElseThrow().getDeletedAt())
                .isCloseTo(link.getDeletedAt(), within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("Expiry can be cleared explicitly; blank fields and mixed expiry flags are rejected")
    void expiryCanBeClearedAndBlankFieldsRejected() throws Exception {
        Registered owner = register("manage-expiry@example.com");
        Link link = seed("expiry1", owner.id(), Instant.now().plus(Duration.ofDays(5)));
        String url = LINKS + "/" + link.getId();
        String bearer = "Bearer " + owner.token();

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearExpiry\":true,\"expiresAt\":\""
                                + Instant.now().plus(Duration.ofDays(3)) + "\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearExpiry\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("expiry1"));

        assertThat(links.findById(link.getId()).orElseThrow().getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("An expired link's detail reports the derived status, not the stored one")
    void detailReportsEffectiveStatus() throws Exception {
        Registered owner = register("manage-effective@example.com");
        Link link = seed("effective1", owner.id(), Instant.now().minusSeconds(60));

        mockMvc.perform(get(LINKS + "/" + link.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveStatus").value("EXPIRED"));
    }

    private Registered register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.user.id");
        return new Registered(id.longValue(), JsonPath.read(body, "$.accessToken"));
    }

    private Link seed(String code, Long ownerId, Instant expiresAt) {
        Link link = new Link();
        link.setShortCode(code);
        link.setUrl("https://example.com/" + code);
        link.setOwnerId(ownerId);
        link.setExpiresAt(expiresAt);
        return links.saveAndFlush(link);
    }

    private record Registered(Long id, String token) {
    }
}
