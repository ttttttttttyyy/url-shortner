package com.url.shortly.service;

import com.url.shortly.enums.PlanTier;

/**
 * Resolves the effective plan tier for an account.
 *
 * <p>FR-05 issues access tokens while no billing provider exists, so the
 * default implementation reports {@link PlanTier#FREE} for everyone. FR-15
 * replaces it with subscription lookup; callers (JWT claims, profiles and
 * entitlement checks) need no further changes.
 */
public interface PlanTierResolver {

    PlanTier tierFor(Long userId);
}
