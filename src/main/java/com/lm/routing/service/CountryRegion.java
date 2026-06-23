package com.lm.routing.service;

import java.util.List;

/**
 * Supported countries/regions with their preferred distance matrix provider rankings.
 *
 * The preference list is ordered from best to worst for that region.
 * In AUTO mode, {@link ProviderSelector} picks the first available provider
 * from the detected country's preference list.
 *
 * Preference rationale based on:
 * - Coverage quality and road network data completeness
 * - Local traffic data and real-time updates
 * - API cost per region
 * - Regulatory compliance (e.g., AMap required for China)
 */
public enum CountryRegion {

    /** China — AMap (高德) has best local road coverage and is regulatorily preferred. */
    CHINA(List.of("AMAP_WAYPOINTS", "OSRM", "GOOGLE_FULL", "MAPBOX")),

    /** United States — Mapbox and Google both excellent; Mapbox often cheaper at scale. */
    USA(List.of("MAPBOX", "GOOGLE_FULL", "BING", "OSRM")),

    /** Canada — Mapbox has strong North American coverage. */
    CANADA(List.of("MAPBOX", "GOOGLE_FULL", "BING", "OSRM")),

    /** United Kingdom — OSRM/OSM data is excellent; Mapbox and Google also good. */
    UK(List.of("OSRM", "MAPBOX", "GOOGLE_FULL", "BING")),

    /** European Union / EEA — OSRM with OSM data is outstanding in Europe. */
    EU_EEA(List.of("OSRM", "MAPBOX", "GOOGLE_FULL", "BING")),

    /** Japan — Google Maps has the best Japan coverage and routing accuracy. */
    JAPAN(List.of("GOOGLE_FULL", "MAPBOX", "OSRM")),

    /** South Korea — Google Maps is most accurate for Korean roads. */
    KOREA(List.of("GOOGLE_FULL", "MAPBOX", "OSRM")),

    /** India — Mapbox has invested heavily in Indian road data; Google also strong. */
    INDIA(List.of("MAPBOX", "GOOGLE_FULL", "OSRM")),

    /** Southeast Asia — Google has best coverage across Thailand, Vietnam, Indonesia, etc. */
    SOUTHEAST_ASIA(List.of("GOOGLE_FULL", "AMAP_WAYPOINTS", "MAPBOX", "OSRM")),

    /** Australia — Mapbox and Google both have excellent coverage. */
    AUSTRALIA(List.of("MAPBOX", "GOOGLE_FULL", "OSRM")),

    /** New Zealand — Mapbox and Google both good. */
    NEW_ZEALAND(List.of("MAPBOX", "GOOGLE_FULL", "OSRM")),

    /** Brazil — Google Maps has the most comprehensive Brazilian road data. */
    BRAZIL(List.of("GOOGLE_FULL", "MAPBOX", "OSRM")),

    /** Latin America (excluding Brazil) — Google generally best coverage. */
    LATIN_AMERICA(List.of("GOOGLE_FULL", "MAPBOX", "OSRM")),

    /** Middle East — Google has best coverage; Mapbox growing. */
    MIDDLE_EAST(List.of("GOOGLE_FULL", "MAPBOX", "OSRM")),

    /** Africa — Google most consistent across the continent; OSRM good in some regions. */
    AFRICA(List.of("GOOGLE_FULL", "OSRM", "MAPBOX")),

    /** Russia — OSRM/OSM has good Russian coverage; Google also available. */
    RUSSIA(List.of("OSRM", "GOOGLE_FULL", "MAPBOX")),

    /** Global fallback when no specific region is matched. */
    GLOBAL(List.of("GOOGLE_FULL", "OSRM", "MAPBOX", "BING"));

    private final List<String> preferredProviders;

    CountryRegion(List<String> preferredProviders) {
        this.preferredProviders = preferredProviders;
    }

    /**
     * Get the ordered list of preferred provider names for this region.
     * First item = best choice (if available).
     */
    public List<String> getPreferredProviders() {
        return preferredProviders;
    }
}
