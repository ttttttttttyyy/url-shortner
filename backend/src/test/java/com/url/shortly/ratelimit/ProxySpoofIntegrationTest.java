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
 * FR-04: a directly exposed port must not let clients mint new rate-limit
 * buckets by rotating {@code X-Forwarded-For}.
 *
 * <p>Uses a real HTTP server on a random port so Tomcat's {@code RemoteIpValve}
 * actually runs. MockMvc sets {@code remoteAddr} synthetically and would not
 * exercise the trusted-proxy decision at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shortly-proxy-spoof;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.window=1m",
        "app.security.rate-limit.guest-link-creation=2",
        "app.security.rate-limit.guest-link-creation-daily=50",
        // The test client connects from loopback; make loopback untrusted so a
        // spoofed header must be ignored.
        "server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.255"
})
class ProxySpoofIntegrationTest {

    private static final String BODY = "{\"url\":\"https://example.com/target\"}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("rotating X-Forwarded-For from an untrusted peer does not reset the bucket")
    void spoofedForwardedAddressIsIgnored() {
        assertThat(postAs("203.0.113.20").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postAs("203.0.113.20").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // If the valve trusted the header this would be a new bucket and 201.
        assertThat(postAs("203.0.113.21").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> postAs(String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return restTemplate.postForEntity(
                "/api/v1/links", new HttpEntity<>(BODY, headers), String.class);
    }
}
