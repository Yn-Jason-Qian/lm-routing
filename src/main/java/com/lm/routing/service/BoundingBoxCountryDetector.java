package com.lm.routing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Country detection using coordinate bounding boxes.
 *
 * Fast, free, no API calls. Accuracy is sufficient for warehouse-level
 * decisions (selecting the optimal map provider). Edge cases near borders
 * may misclassify → safely falls back to {@link CountryRegion#GLOBAL}.
 *
 * Smaller/more specific regions are checked first to minimize overlap ambiguity.
 */
@Slf4j
@Service
public class BoundingBoxCountryDetector implements CountryDetector {

    /**
     * Bounding box: [minLat, maxLat, minLng, maxLng].
     * Ordered from most specific (small islands) to least specific (continents).
     */
    private static final Object[][] BOXES = {
            // [CountryRegion, minLat, maxLat, minLng, maxLng]

            // ——— Small islands / peninsulas first ———
            {CountryRegion.NEW_ZEALAND,   -47.3, -34.4,  166.4,  178.6},
            {CountryRegion.UK,             49.9,  58.7,   -7.6,    1.8},
            {CountryRegion.KOREA,          33.1,  38.6,  124.6,  131.9},
            {CountryRegion.JAPAN,          30.0,  45.5,  129.5,  146.0},

            // ——— Medium regions ———
            {CountryRegion.AUSTRALIA,     -43.6, -10.0,  113.0,  154.0},
            {CountryRegion.INDIA,           6.5,  35.5,   68.1,   97.4},
            {CountryRegion.BRAZIL,        -33.7,   5.3,  -73.9,  -34.8},

            // ——— Large regions with explicit bounds ———
            {CountryRegion.SOUTHEAST_ASIA, -10.0, 28.5,   95.0,  141.0},
            {CountryRegion.CHINA,          18.0,  53.5,   73.5,  135.0},
            {CountryRegion.USA,            24.5,  49.4, -125.0,  -66.9},
            {CountryRegion.CANADA,         41.7,  83.1, -141.0,  -52.6},
            {CountryRegion.MIDDLE_EAST,    12.0,  42.0,   26.0,   63.3},
            {CountryRegion.RUSSIA,         41.2,  81.8,   19.6,  169.0}, // European + Asian Russia

            // ——— Broader continental regions ———
            {CountryRegion.EU_EEA,         35.0,  71.0,  -10.0,   31.0},
            {CountryRegion.AFRICA,        -35.0,  37.0,  -18.0,   52.0},
            {CountryRegion.LATIN_AMERICA, -55.0,  32.7, -117.1,  -34.8},
    };

    @Override
    public CountryRegion detectCountry(double lat, double lng) {
        for (Object[] box : BOXES) {
            CountryRegion region = (CountryRegion) box[0];
            double minLat = (double) box[1];
            double maxLat = (double) box[2];
            double minLng = (double) box[3];
            double maxLng = (double) box[4];

            if (lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng) {
                log.debug("Country detected: {} (lat={}, lng={})", region.name(), lat, lng);
                return region;
            }
        }

        log.debug("No specific region matched for lat={}, lng={} → GLOBAL", lat, lng);
        return CountryRegion.GLOBAL;
    }
}
