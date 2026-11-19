package com.url.shortly.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the same RFC 9457 contract for missing and invalid access tokens.
 *
 * <p>Filter-chain failures never reach {@code @RestControllerAdvice}, so this
 * class serializes the problem detail directly. Detail text is intentionally
 * generic: it must not reveal whether a token was malformed, expired or
 * signed with the wrong key.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"shortly\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Authentication required");
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("detail", "A valid access token is required.");
        body.put("instance", request.getRequestURI());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
