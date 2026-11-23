package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * FR-04: when the direct peer is a trusted proxy, the forwarded client address
 * is the rate-limit identity.
 *
 * <p>This is the positive half of the proxy-spoof test: it proves the
 * container valve is actually active. The untrusted half lives in
 * {@link ProxySpoofIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly-proxy-trusted;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.window=1m",
        "app.security.rate-limit.guest-link-creation=2",
        "app.security.rate-limit.guest-link-creation-daily=50",
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|::1"
})
class ProxyTrustIntegrationTest {

    private static final String BODY = "{\"url\":\"https://example.com/target\"}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("a trusted peer's X-Forwarded-For selects independent buckets")
    void forwardedAddressIsUsedWhenPeerIsTrusted() {
        assertThat(postAs("203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postAs("203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postAs("203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A different forwarded client must not inherit the exhausted bucket.
        assertThat(postAs("203.0.113.6").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> postAs(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return restTemplate.postForEntity(
                "/api/v1/links", new HttpEntity<>(BODY, headers), String.class);
    }
}
