package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pure Haversine spherical approximation — free, always available.
 *
 * Priority 99: Ultimate fallback when no external API is configured.
 */
@Slf4j
@Service
public class HaversineOnlyProvider implements MatrixProvider {

    private final HaversineMatrixService haversineService;

    public HaversineOnlyProvider(HaversineMatrixService haversineService) {
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "HAVERSINE_ONLY";
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available
    }

    @Override
    public int getPriority() {
        return 99;
    }

    @Override
    public boolean isRealRoad() {
        return false;
    }

    @Override
    public ProviderCapability getCapability() {
        return ProviderCapability.HAVERSINE_ONLY;
    }

    @Override
    public MatrixResult buildMatrix(List<GeoPoint> allPoints, GeoPoint warehouse) {
        log.info("Haversine Only: building matrix for {} points (free, approximate)", allPoints.size());
        double[][] matrix = haversineService.buildMatrix(allPoints);
        return new MatrixResult(matrix, "HAVERSINE_ONLY",
                "Free spherical distance (no API configured)", 0, false);
    }
}
