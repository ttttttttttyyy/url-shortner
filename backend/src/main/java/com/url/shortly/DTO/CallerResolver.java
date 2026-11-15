package com.url.shortly.DTO;

import org.springframework.stereotype.Component;

@Component
public class CallerResolver {
    /** Replace with the authenticated principal once security lands. */
    public CallerContext current() {
        return CallerContext.guest();
    }
}
