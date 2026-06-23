package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import com.lm.routing.service.provider.mapbox.MapboxApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Mapbox Directions Matrix provider — real road distance matrix via Mapbox API.
 *
 * Cost: ~$0.001/element. Max 25 coordinates per API request.
 * Excellent coverage in North America, EU, Australia, India.
 *
 * Priority 3: Good paid option, cheaper than Google at scale.
 */
@Slf4j
@Service
public class MapboxMatrixProvider implements MatrixProvider {

    private final MapboxApiService mapboxApiService;
    private final HaversineMatrixService haversineService;

    public MapboxMatrixProvider(MapboxApiService mapboxApiService,
                                 HaversineMatrixService haversineService) {
        this.mapboxApiService = mapboxApiService;
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "MAPBOX";
    }

    @Override
    public boolean isAvailable() {
        return mapboxApiService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 3;
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
        int n = allPoints.size();
        long elements = (long) n * n;
        log.info("Mapbox: building {}×{} matrix ({} elements)", n, n, elements);

        double[][] matrix = mapboxApiService.buildMatrix(allPoints);
        if (matrix != null && matrix.length == n) {
            return new MatrixResult(matrix, "MAPBOX",
                    "Mapbox Directions Matrix — real road distances", elements, true);
        }

        log.warn("Mapbox matrix failed, falling back to Haversine");
        double[][] fallback = haversineService.buildMatrix(allPoints);
        return new MatrixResult(fallback, "MAPBOX_FALLBACK_TO_HAVERSINE",
                "Mapbox unavailable, Haversine fallback", 0, false);
    }
}
