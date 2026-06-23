package com.lm.routing.service;

/**
 * Detects which country/region a coordinate falls into.
 *
 * Implementations may use bounding boxes, reverse geocoding APIs,
 * or shapefile lookups.
 */
@FunctionalInterface
public interface CountryDetector {

    /**
     * Detect the country/region for a geographic coordinate.
     *
     * @param lat latitude (-90 to 90)
     * @param lng longitude (-180 to 180)
     * @return the detected region (never null — falls back to {@link CountryRegion#GLOBAL})
     */
    CountryRegion detectCountry(double lat, double lng);
}
