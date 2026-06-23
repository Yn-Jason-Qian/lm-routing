package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import com.lm.routing.service.provider.bing.BingMapsApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bing Maps Distance Matrix provider — real road distance matrix via Bing Maps API.
 *
 * Cost: ~$0.002/element. Supports truck routing attributes.
 * Good coverage globally, especially strong in North America and Europe.
 *
 * Priority 6: Good alternative when Mapbox/Google are unavailable.
 */
@Slf4j
@Service
public class BingMapsMatrixProvider implements MatrixProvider {

    private final BingMapsApiService bingMapsApiService;
    private final HaversineMatrixService haversineService;

    public BingMapsMatrixProvider(BingMapsApiService bingMapsApiService,
                                   HaversineMatrixService haversineService) {
        this.bingMapsApiService = bingMapsApiService;
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "BING";
    }

    @Override
    public boolean isAvailable() {
        return bingMapsApiService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 6;
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
        log.info("Bing Maps: building {}×{} matrix ({} elements)", n, n, elements);

        double[][] matrix = bingMapsApiService.buildMatrix(allPoints);
        if (matrix != null && matrix.length == n) {
            return new MatrixResult(matrix, "BING",
                    "Bing Maps Distance Matrix — real road distances", elements, true);
        }

        log.warn("Bing Maps matrix failed, falling back to Haversine");
        double[][] fallback = haversineService.buildMatrix(allPoints);
        return new MatrixResult(fallback, "BING_FALLBACK_TO_HAVERSINE",
                "Bing Maps unavailable, Haversine fallback", 0, false);
    }
}
