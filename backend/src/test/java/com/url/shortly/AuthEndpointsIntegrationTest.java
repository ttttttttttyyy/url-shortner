package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.url.shortly.Entities.AppUser;
import com.url.shortly.Entities.AuthSession;
import com.url.shortly.Entities.Link;
import com.url.shortly.Repository.AppUserRepository;
import com.url.shortly.Repository.AuthSessionRepository;
import com.url.shortly.Repository.LinkRepository;
import com.url.shortly.enums.UserStatus;
import com.url.shortly.security.TokenHasher;

import jakarta.servlet.http.Cookie;

/**
 * FR-05 end-to-end contract for the authentication endpoints.
 *
 * <p>Rate limits are raised for this class so functional assertions are never
 * masked by throttling; the limits themselves are covered by
 * {@code AuthRateLimitIntegrationTest}. The datasource name is unique so this
 * context never shares tables with other integration suites.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly_auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.registration-attempts=1000",
        "app.security.rate-limit.login-ip-attempts=1000",
        "app.security.rate-limit.login-attempts=1000"
})
class AuthEndpointsIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";
    private static final String LINKS = "/api/v1/links";
    private static final String REFRESH_COOKIE = "SHORTLY_REFRESH";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AuthSessionRepository sessions;

    @Autowired
    private LinkRepository links;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Registration normalizes the email, signs in and sets a hardened refresh cookie")
    void registrationCreatesAccountAndSession() throws Exception {
        MvcResult result = register("Alice@Example.com", "correct-horse", "Alice");

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("no-store");

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains(REFRESH_COOKIE + "=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=604800")
                .doesNotContain("Domain=")
                // The test profile overrides the production Secure default.
                .doesNotContain("Secure");

        String rawRefreshToken = refreshToken(result);
        assertThat(rawRefreshToken).hasSizeGreaterThanOrEqualTo(43);

        AppUser user = users.findByEmail("alice@example.com").orElseThrow();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.getPasswordHash()).isNotEqualTo("correct-horse");
        assertThat(passwordEncoder.matches("correct-horse", user.getPasswordHash())).isTrue();

        AuthSession session = sessions
                .findByRefreshTokenHash(TokenHasher.sha256Hex(rawRefreshToken))
                .orElseThrow();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getFamilyId()).isNotBlank();
        assertThat(session.getExpiresAt()).isAfter(Instant.now());

        Jwt accessToken = jwtDecoder.decode(accessToken(result));
        assertThat(accessToken.getSubject()).isEqualTo(user.getId().toString());
        assertThat(accessToken.getClaimAsString("email")).isEqualTo("alice@example.com");
        assertThat(accessToken.getClaimAsString("role")).isEqualTo("USER");
        assertThat(accessToken.getClaimAsString("tier")).isEqualTo("FREE");
        assertThat(accessToken.getAudience()).contains("shortly-web");
        assertThat(accessToken.getClaimAsString("iss")).isEqualTo("shortly");
    }

    @Test
    @DisplayName("Duplicate registration is rejected case-insensitively with 409")
    void duplicateEmailIsRejected() throws Exception {
        register("duplicate@example.com", "correct-horse", null);

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("DUPLICATE@Example.Com", "correct-horse", null)))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.title").value("Email already registered"));

        assertThat(users.findByEmail("duplicate@example.com")).isPresent();
    }

    @Test
    @DisplayName("A password above bcrypt's 72-byte input limit is rejected")
    void overlongPasswordIsRejected() throws Exception {
        // 40 characters, 80 UTF-8 bytes: passes @Size but not bcrypt's byte limit.
        String password = "é".repeat(40);

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("long-password@example.com", password, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid password"))
                .andExpect(jsonPath("$.detail").value(containsString("72 bytes")));

        assertThat(users.findByEmail("long-password@example.com")).isEmpty();
    }

    @Test
    @DisplayName("Login returns a new session and wrong credentials stay generic")
    void loginSucceedsAndFailsGenerically() throws Exception {
        register("login@example.com", "correct-horse", null);

        MvcResult login = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("login@example.com", "correct-horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("login@example.com"))
                .andReturn();

        assertThat(accessToken(login)).isNotBlank();
        assertThat(refreshToken(login)).isNotBlank();

        MvcResult wrongPassword = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("login@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("nobody@example.com", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Identical bodies prevent the endpoint from revealing account existence.
        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
        assertThat(wrongPassword.getResponse().getContentAsString())
                .contains("Email or password is incorrect.");
    }

    @Test
    @DisplayName("The profile endpoint requires a valid bearer token")
    void profileRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")))
                .andExpect(jsonPath("$.title").value("Authentication required"));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));

        MvcResult registration = register("profile@example.com", "correct-horse", "Profile User");
        mockMvc.perform(get(ME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(registration)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.email").value("profile@example.com"))
                .andExpect(jsonPath("$.displayName").value("Profile User"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.planTier").value("FREE"))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    @DisplayName("Refresh rotates the token, and reuse revokes the whole family")
    void refreshRotatesAndDetectsReuse() throws Exception {
        MvcResult registration = register("rotate@example.com", "correct-horse", null);
        String firstToken = refreshToken(registration);

        MvcResult refreshed = mockMvc.perform(post(REFRESH)
                        .cookie(new Cookie(REFRESH_COOKIE, firstToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        String secondToken = refreshToken(refreshed);

        assertThat(secondToken).isNotEqualTo(firstToken);

        AuthSession firstSession = session(firstToken);
        AuthSession secondSession = session(secondToken);
        assertThat(firstSession.getRevokedAt()).isNotNull();
        assertThat(secondSession.getRevokedAt()).isNull();
        assertThat(secondSession.getFamilyId()).isEqualTo(firstSession.getFamilyId());

        // Presenting the already-rotated token revokes every session in the chain.
        mockMvc.perform(post(REFRESH).cookie(new Cookie(REFRESH_COOKIE, firstToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(jsonPath("$.title").value("Invalid session"));

        assertThat(session(secondToken).getRevokedAt()).isNotNull();

        // The legitimate rotated token is now dead too.
        mockMvc.perform(post(REFRESH).cookie(new Cookie(REFRESH_COOKIE, secondToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An expired refresh session is rejected and marked revoked")
    void expiredRefreshSessionIsRejected() throws Exception {
        MvcResult registration = register("expired@example.com", "correct-horse", null);
        String rawToken = refreshToken(registration);

        AuthSession session = session(rawToken);
        session.setExpiresAt(Instant.now().minusSeconds(60));
        sessions.saveAndFlush(session);

        mockMvc.perform(post(REFRESH).cookie(new Cookie(REFRESH_COOKIE, rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid session"));

        assertThat(session(rawToken).getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("A missing refresh cookie is rejected without creating state")
    void missingRefreshCookieIsRejected() throws Exception {
        long before = sessions.count();

        mockMvc.perform(post(REFRESH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid session"));

        assertThat(sessions.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("Logout revokes the session, clears the cookie and is idempotent")
    void logoutRevokesSession() throws Exception {
        MvcResult registration = register("logout@example.com", "correct-horse", null);
        String rawToken = refreshToken(registration);
        String bearer = accessToken(registration);

        mockMvc.perform(post(LOGOUT).cookie(new Cookie(REFRESH_COOKIE, rawToken)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));

        assertThat(session(rawToken).getRevokedAt()).isNotNull();

        // Logging out twice stays successful and cannot revive the session.
        mockMvc.perform(post(LOGOUT).cookie(new Cookie(REFRESH_COOKIE, rawToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(REFRESH).cookie(new Cookie(REFRESH_COOKIE, rawToken)))
                .andExpect(status().isUnauthorized());

        // Access tokens are stateless: they stay valid until their short expiry.
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CORS preflight accepts only configured origins")
    void corsPreflightUsesAllowList() throws Exception {
        mockMvc.perform(options(LOGIN)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));

        mockMvc.perform(options(LOGIN)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An invalid bearer token is rejected even on a public endpoint")
    void invalidTokenDoesNotSilentlyDegradeToGuest() throws Exception {
        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tampered.token.value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/guest\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    @DisplayName("Authenticated link creation records the owner while guests stay anonymous")
    void authenticatedCreationRecordsOwner() throws Exception {
        MvcResult registration = register("owner@example.com", "correct-horse", null);
        Number idClaim = JsonPath.read(
                registration.getResponse().getContentAsString(), "$.user.id");
        Long userId = idClaim.longValue();

        mockMvc.perform(post(LINKS)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(registration))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/owned\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationUrl").value("https://example.com/owned"));

        List<Link> owned = links.findByOwnerIdOrderByCreatedAtDesc(userId);
        assertThat(owned).hasSize(1);
        assertThat(owned.get(0).getOwnerId()).isEqualTo(userId);

        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/guest\"}"))
                .andExpect(status().isCreated());

        // The guest link did not join the account's collection.
        assertThat(links.findByOwnerIdOrderByCreatedAtDesc(userId)).hasSize(1);
    }

    @Test
    @DisplayName("A disabled account cannot use an otherwise valid access token")
    void disabledAccountCannotReadProfile() throws Exception {
        MvcResult registration = register("disabled@example.com", "correct-horse", null);
        String bearer = accessToken(registration);

        AppUser user = users.findByEmail("disabled@example.com").orElseThrow();
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    private MvcResult register(String email, String password, String displayName) throws Exception {
        return mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, password, displayName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email.trim().toLowerCase()))
                .andReturn();
    }

    private AuthSession session(String rawToken) {
        return sessions.findByRefreshTokenHash(TokenHasher.sha256Hex(rawToken)).orElseThrow();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String refreshToken(MvcResult result) throws Exception {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        String pair = setCookie.substring(0, setCookie.indexOf(';'));
        return pair.substring(pair.indexOf('=') + 1);
    }

    private static String registerJson(String email, String password, String displayName) {
        String name = displayName == null ? "" : ",\"displayName\":\"" + displayName + "\"";
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + name + "}";
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
