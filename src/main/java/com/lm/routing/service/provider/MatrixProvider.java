package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;

import java.util.List;

/**
 * Strategy interface for building distance matrices.
 *
 * Each implementation represents one approach to computing the N×N distance
 * matrix required by the TSP solver — ranging from free Haversine approximation
 * to full real-road matrices via external APIs (OSRM, Google, Mapbox, Bing, etc.).
 *
 * Spring auto-discovers all {@code @Service} beans implementing this interface
 * and {@link ProviderSelector} picks the best one based on country and availability.
 */
public interface MatrixProvider {

    /**
     * Unique strategy name used in configuration and logging.
     * Example: "OSRM", "MAPBOX", "AMAP_WAYPOINTS"
     */
    String getName();

    /**
     * Whether this provider is currently usable (API key configured, service reachable).
     */
    boolean isAvailable();

    /**
     * Build the full N×N distance matrix.
     *
     * @param allPoints all geographic points — warehouse at index 0, stops at indices 1..n-1
     * @param warehouse warehouse coordinate (convenience, also at allPoints[0])
     * @return matrix result with metadata about the strategy used
     */
    MatrixResult buildMatrix(List<GeoPoint> allPoints, GeoPoint warehouse);

    /**
     * Priority for ordering when multiple providers are available.
     * Lower number = higher priority. Used in AUTO mode as a tiebreaker
     * within the same country preference tier.
     */
    int getPriority();

    /**
     * Whether this provider produces real road distances (true) or approximations (false).
     */
    boolean isRealRoad();

    /**
     * The capability classification of this provider.
     */
    ProviderCapability getCapability();
}
