package com.url.shortly.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.url.shortly.enums.PlanTier;
import com.url.shortly.enums.UserRole;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates {@code Authorization: Bearer <JWT>} and populates the security
 * context with an {@link AuthenticatedUser}.
 *
 * <p>No header means anonymous: public endpoints continue normally and
 * protected endpoints are rejected later by the authorization rules. A header
 * that is present but invalid is rejected immediately with the standard 401
 * problem detail, including on public endpoints, so a stale client token never
 * silently degrades into a guest request.
 *
 * <p>This class is deliberately not a {@code @Component}: it is constructed by
 * {@link SecurityConfig} so {@code @WebMvcTest} slices do not try to
 * instantiate a filter that needs the JWT decoder.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtDecoder jwtDecoder,
                                   ProblemDetailAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            reject(request, response);
            return;
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            AuthenticatedUser principal = toPrincipal(jwt);

            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            principal, null, principal.authorities());
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException ex) {
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response,
                new BadCredentialsException("Invalid access token"));
    }

    private AuthenticatedUser toPrincipal(Jwt jwt) {
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String role = jwt.getClaimAsString("role");
        String tier = jwt.getClaimAsString("tier");
        if (subject == null || email == null || role == null || tier == null) {
            throw new BadJwtException("Access token is missing required claims");
        }

        try {
            return new AuthenticatedUser(
                    Long.valueOf(subject),
                    email,
                    UserRole.valueOf(role),
                    PlanTier.valueOf(tier));
        } catch (IllegalArgumentException ex) {
            throw new BadJwtException("Access token contains invalid claims", ex);
        }
    }
}
