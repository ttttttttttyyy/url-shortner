package com.url.shortly.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives an opaque, salted client key for rate limiting.
 *
 * <p>The raw address is never stored, logged or used as a metric tag. The
 * container's trusted-proxy support (Tomcat {@code RemoteIpValve}) is
 * responsible for making {@link HttpServletRequest#getRemoteAddr()} the real
 * client address; this class deliberately does not parse forwarding headers
 * itself.
 */
@Component
public class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private final byte[] salt = new byte[32];

    public ClientIpResolver() {
        new SecureRandom().nextBytes(salt);
    }

    /** @return 64-character hex SHA-256 of salt + address, never the address */
    public String key(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            remoteAddress = UNKNOWN;
        }
        return sha256Hex(salt, remoteAddress);
    }

    private static String sha256Hex(byte[] salt, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
