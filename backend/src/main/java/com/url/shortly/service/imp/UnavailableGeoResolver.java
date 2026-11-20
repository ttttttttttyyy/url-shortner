package com.url.shortly.service.imp;

import org.springframework.stereotype.Component;

import com.url.shortly.service.GeoResolver;

import jakarta.servlet.http.HttpServletRequest;

/** Default geo provider until a GeoIP source is selected and configured. */
@Component
public class UnavailableGeoResolver implements GeoResolver {

    @Override
    public String countryFor(HttpServletRequest request) {
        return null;
    }
}
