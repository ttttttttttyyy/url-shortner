package com.url.shortly.service;

import java.util.ArrayList;

import com.url.shortly.DTO.CallerContext;
import com.url.shortly.DTO.CreateLinkRequest;
import com.url.shortly.Entities.Link;

/**
 * LinkService
 */ 
public interface LinkService {

    ArrayList<Link> getUserShortLinks();
    
    Link createLink(CreateLinkRequest request, CallerContext caller);

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
