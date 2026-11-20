package com.url.shortly.service;

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
 */
public record OwnedLinks(List<Link> links,
                         long totalElements,
                         int totalPages,
                         UsageSummary usage) {

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
