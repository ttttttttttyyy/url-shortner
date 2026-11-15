package com.url.shortly.DTO;

import com.url.shortly.enums.PlanTier;

public record CallerContext(Long userId, PlanTier tier) {
    public static CallerContext guest() {
        return new CallerContext(null, PlanTier.GUEST);
    }
}
