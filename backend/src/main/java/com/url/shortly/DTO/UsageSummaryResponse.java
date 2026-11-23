package com.url.shortly.DTO;

import com.url.shortly.service.OwnedLinks.UsageSummary;

/**
 * Account-wide counts for the dashboard. Deleted links are excluded.
 *
 * <p>{@code activeLinks} excludes expired links even though their stored status
 * is still {@code ACTIVE}; {@code expiredLinks} counts them separately. No
 * click metrics are included until FR-11 introduces click collection.
 */
public record UsageSummaryResponse(
        long totalLinks,
        long activeLinks,
        long disabledLinks,
        long blockedLinks,
        long expiredLinks,
        long customAliases,
        long createdLast30Days,
        int maxLinks) {

    public static UsageSummaryResponse from(UsageSummary summary) {
        return new UsageSummaryResponse(
                summary.totalLinks(),
                summary.activeLinks(),
                summary.disabledLinks(),
                summary.blockedLinks(),
                summary.expiredLinks(),
                summary.customAliases(),
                summary.createdLast30Days(),
                summary.maxLinks());
    }
}
