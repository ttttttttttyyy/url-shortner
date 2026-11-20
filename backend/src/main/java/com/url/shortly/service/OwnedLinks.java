package com.url.shortly.service;

import java.time.Instant;
import java.util.List;

import com.url.shortly.Entities.Link;

/**
 * One dashboard read: a page of the owner's links plus an account-wide usage
 * summary computed independently of the filter and page.
 *
 * @param links          links on the requested page, newest first
 * @param totalElements  number of links matching the filter
 * @param totalPages     number of pages for that filter
 * @param usage          account-wide counts over all non-deleted links
 * @param now            the application clock instant used for this read, so
 *                       derived expiry in the response matches the summary
 */
public record OwnedLinks(List<Link> links,
                         long totalElements,
                         int totalPages,
                         UsageSummary usage,
                         Instant now) {

    /** Counts the dashboard needs; no click metrics until FR-11 adds them. */
    public record UsageSummary(long totalLinks,
                               long activeLinks,
                               long disabledLinks,
                               long blockedLinks,
                               long expiredLinks,
                               long customAliases,
                               long createdLast30Days,
                               int maxLinks) {
    }
}
