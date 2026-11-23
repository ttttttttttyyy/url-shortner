package com.url.shortly.service.imp;

import org.springframework.stereotype.Component;

import com.url.shortly.enums.PlanTier;
import com.url.shortly.service.PlanTierResolver;

/** Until FR-15 adds subscriptions, every registered account is on the free plan. */
@Component
public class FreePlanTierResolver implements PlanTierResolver {

    @Override
    public PlanTier tierFor(Long userId) {
        return PlanTier.FREE;
    }
}
