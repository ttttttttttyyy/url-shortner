package com.url.shortly.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.url.shortly.enums.PlanTier;
import com.url.shortly.enums.UserRole;

/**
 * Security principal reconstructed from validated access-token claims.
 *
 * <p>It deliberately contains no password material or database entity, so it
 * is safe to keep in the request-scoped security context.
 */
public record AuthenticatedUser(Long id, String email, UserRole role, PlanTier planTier) {

    public List<GrantedAuthority> authorities() {
        return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
