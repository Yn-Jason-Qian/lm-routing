package com.lm.routing.service.provider;

/**
 * Classifies what kind of distance matrix a provider produces.
 */
public enum ProviderCapability {

    /** Complete real road distance matrix via external API (OSRM, Google, Mapbox, Bing). */
    FULL_REAL_MATRIX,

    /** Real road matrix built via K-means clustering to reduce API costs (Google Cluster). */
    CLUSTER_HYBRID,

    /** Haversine approximate matrix + post-TSP real road enrichment via waypoint API (AMap, AWS). */
    HAVERSINE_WITH_ENRICHMENT,

    /** Pure Haversine spherical approximation — no external service needed. */
    HAVERSINE_ONLY
}
