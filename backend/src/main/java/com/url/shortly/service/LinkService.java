package com.url.shortly.service;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.DTO.UpdateLinkRequest;
import com.url.shortly.Entities.Link;
import com.url.shortly.Entities.LinkStatus;

/**
 * Link creation, resolution and dashboard reads.
 */
public interface LinkService {

    /**
     * Read one page of links owned by {@code ownerId}, with account-wide usage
     * counts. The owner id always comes from the authenticated principal, never
     * from a request parameter.
     *
     * @param status optional status filter; {@code null} means every non-deleted link
     */
    OwnedLinks listOwnedLinks(Long ownerId, int page, int size, LinkStatus status);

    Link createLink(CreateLinkRequest request, CallerContext caller);

    /** Read one link owned by {@code ownerId}; soft-deleted rows are not readable. */
    Link getOwnedLink(Long ownerId, Long linkId);

    /**
     * Update permitted properties of one link owned by the caller. The caller's
     * tier decides whether a custom alias is allowed (FR-08).
     */
    Link updateOwnedLink(CallerContext caller, Long linkId, UpdateLinkRequest request);

    /** Soft-delete one link owned by {@code ownerId}; idempotent. */
    void deleteOwnedLink(Long ownerId, Long linkId);

    /**
     * Resolve a public short code according to the FR-03 policy.
     *
     * <p>Returns a {@link LinkResolution} instead of a raw row so callers
     * cannot accidentally redirect a disabled, blocked, expired or deleted
     * link. An unknown code is reported as
     * {@link LinkResolution.Outcome#NOT_FOUND}, not as an exception.
     */
    LinkResolution resolve(String shortCode);

}
