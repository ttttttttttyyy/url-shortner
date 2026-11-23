package com.url.shortly.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * FR-04: the limiter key must be opaque and stable per client address.
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("the key is a salted SHA-256 hex digest, not the address")
    void keyIsOpaque() {
        ClientIpResolver resolver = new ClientIpResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        String key = resolver.key(request);

        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key).doesNotContain("203.0.113.9");
    }

    @Test
    @DisplayName("the same address yields the same key within one instance")
    void keyIsStablePerAddress() {
        ClientIpResolver resolver = new ClientIpResolver();

        assertThat(resolver.key(request("198.51.100.7")))
                .isEqualTo(resolver.key(request("198.51.100.7")));
    }

    @Test
    @DisplayName("different addresses yield different keys")
    void keysDifferPerAddress() {
        ClientIpResolver resolver = new ClientIpResolver();

        assertThat(resolver.key(request("198.51.100.7")))
                .isNotEqualTo(resolver.key(request("198.51.100.8")));
    }

    @Test
    @DisplayName("the salt is per instance so keys are not portable")
    void saltDiffersPerInstance() {
        assertThat(new ClientIpResolver().key(request("198.51.100.7")))
                .isNotEqualTo(new ClientIpResolver().key(request("198.51.100.7")));
    }

    @Test
    @DisplayName("a missing address still produces a deterministic fallback key")
    void missingAddressIsHandled() {
        ClientIpResolver resolver = new ClientIpResolver();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(" ");

        assertThat(resolver.key(request)).matches("[0-9a-f]{64}");
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
