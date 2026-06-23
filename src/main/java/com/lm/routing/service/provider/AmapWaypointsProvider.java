package com.lm.routing.service.provider;

import com.lm.routing.service.AmapRouteService;
import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AMap (高德地图) waypoint enrichment strategy.
 *
 * Builds a free Haversine matrix, then uses AMap driving directions API
 * to fetch real road distances and polylines after TSP solving.
 *
 * Priority 2: AMap has best China coverage and is very cheap per call.
 */
@Slf4j
@Service
public class AmapWaypointsProvider implements MatrixProvider {

    private final HaversineMatrixService haversineService;
    private final AmapRouteService amapRouteService;

    public AmapWaypointsProvider(HaversineMatrixService haversineService,
                                  AmapRouteService amapRouteService) {
        this.haversineService = haversineService;
        this.amapRouteService = amapRouteService;
    }

    @Override
    public String getName() {
        return "AMAP_WAYPOINTS";
    }

    @Override
    public boolean isAvailable() {
        return amapRouteService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isRealRoad() {
        return false; // Matrix is Haversine; enrichment happens post-TSP
    }

    @Override
    public ProviderCapability getCapability() {
        return ProviderCapability.HAVERSINE_WITH_ENRICHMENT;
    }

    @Override
    public MatrixResult buildMatrix(List<GeoPoint> allPoints, GeoPoint warehouse) {
        log.info("AMap Waypoints: Haversine matrix for {} points (enrichment via AMap API after TSP)",
                allPoints.size());
        double[][] matrix = haversineService.buildMatrix(allPoints);
        return new MatrixResult(matrix, "AMAP_WAYPOINTS",
                "Haversine free matrix + AMap waypoint refinement",
                0, false);
    }

    /**
     * Expose the underlying AMap service for post-TSP enrichment in RoutePlanService.
     */
    public AmapRouteService getAmapRouteService() {
        return amapRouteService;
    }
}
