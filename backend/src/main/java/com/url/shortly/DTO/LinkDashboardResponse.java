package com.url.shortly.DTO;

import java.util.List;

import com.url.shortly.service.OwnedLinks;
import com.url.shortly.service.ShortUrlBuilder;

/**
 * Response for {@code GET /api/v1/links}.
 *
 * <p>The summary counts every non-deleted owned link, so it does not change
 * when the frontend narrows the list with a filter or moves to another page.
 */
public record LinkDashboardResponse(
        List<LinkListItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        UsageSummaryResponse summary) {

    public static LinkDashboardResponse from(OwnedLinks result, int page, int size,
                                              ShortUrlBuilder shortUrlBuilder) {
        List<LinkListItem> items = result.links().stream()
                .map(link -> LinkListItem.from(link, shortUrlBuilder.build(link.getShortCode())))
                .toList();

        return new LinkDashboardResponse(
                items,
                page,
                size,
                result.totalElements(),
                result.totalPages(),
                UsageSummaryResponse.from(result.usage()));
    }
}
