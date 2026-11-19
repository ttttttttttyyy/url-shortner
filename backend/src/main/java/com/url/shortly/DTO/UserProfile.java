package com.url.shortly.DTO;

import java.time.Instant;

import com.url.shortly.Entities.AppUser;
import com.url.shortly.enums.PlanTier;
import com.url.shortly.enums.UserRole;

/**
 * Safe account projection. Never includes password material or session data.
 */
public record UserProfile(
        Long id,
        String email,
        String displayName,
        UserRole role,
        PlanTier planTier,
        boolean emailVerified,
        Instant createdAt) {

    public static UserProfile from(AppUser user) {
        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                PlanTier.FREE,
                user.getEmailVerifiedAt() != null,
                user.getCreatedAt());
    }
}
