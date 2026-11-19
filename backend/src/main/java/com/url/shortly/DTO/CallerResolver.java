package com.url.shortly.DTO;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.url.shortly.security.AuthenticatedUser;

/**
 * Resolves the caller for link creation and rate-limit policy selection.
 *
 * <p>Anonymous requests (including Spring Security's anonymous token) remain
 * guests. A validated JWT produces an {@link AuthenticatedUser} principal,
 * which carries the user id and plan tier.
 */
@Component
public class CallerResolver {

    public CallerContext current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return new CallerContext(user.id(), user.planTier());
        }
        return CallerContext.guest();
    }
}
