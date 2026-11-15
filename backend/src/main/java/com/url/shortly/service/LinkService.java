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
    
    Link getOriginalLink(String shortCode);

}
