package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import com.lm.routing.service.OsrmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OSRM full matrix — free, real road distances via self-hosted routing engine.
 *
 * Priority 1: Best option when available (free, accurate, full matrix).
 */
@Slf4j
@Service
public class OsrmMatrixProvider implements MatrixProvider {

    private final OsrmService osrmService;
    private final HaversineMatrixService haversineService;

    public OsrmMatrixProvider(OsrmService osrmService, HaversineMatrixService haversineService) {
        this.osrmService = osrmService;
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "OSRM";
    }

    @Override
    public boolean isAvailable() {
        return osrmService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean isRealRoad() {
        return true;
    }

    @Override
    public ProviderCapability getCapability() {
        return ProviderCapability.FULL_REAL_MATRIX;
    }

    @Override
    public MatrixResult buildMatrix(List<GeoPoint> allPoints, GeoPoint warehouse) {
        log.info("OSRM: building full matrix for {} points", allPoints.size());
        double[][] matrix = osrmService.buildMatrix(allPoints);
        if (matrix != null) {
            return new MatrixResult(matrix, "OSRM",
                    "OSRM self-hosted routing engine", 0, true);
        }
        // OSRM failed, fallback to Haversine
        log.warn("OSRM failed, falling back to Haversine");
        double[][] fallback = haversineService.buildMatrix(allPoints);
        return new MatrixResult(fallback, "OSRM_FALLBACK_TO_HAVERSINE",
                "OSRM unavailable, Haversine fallback", 0, false);
    }
}
