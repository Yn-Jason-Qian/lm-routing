package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.GoogleMapsApiService;
import com.lm.routing.service.HaversineMatrixService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Google full matrix — expensive but simple. Best for ≤50 points.
 *
 * Priority 4: Good fallback when no free option is available.
 */
@Slf4j
@Service
public class GoogleFullMatrixProvider implements MatrixProvider {

    private final GoogleMapsApiService googleMapsApiService;
    private final HaversineMatrixService haversineService;

    @Value("${routing.google.api-key:}")
    private String apiKey;

    public GoogleFullMatrixProvider(GoogleMapsApiService googleMapsApiService,
                                    HaversineMatrixService haversineService) {
        this.googleMapsApiService = googleMapsApiService;
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "GOOGLE_FULL";
    }

    @Override
    public boolean isAvailable() {
        return googleMapsApiService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 4;
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
        log.info("Google Full: {}×{} = {} elements", n, n, elements);

        double[][] result = googleMapsApiService.computeSubMatrix(allPoints, allPoints);
        if (result != null && result.length == n) {
            return new MatrixResult(result, "GOOGLE_FULL",
                    "Google Distance Matrix full", elements, true);
        }

        log.warn("Google full matrix failed, falling back to Haversine");
        double[][] fallback = haversineService.buildMatrix(allPoints);
        return new MatrixResult(fallback, "GOOGLE_FALLBACK_TO_HAVERSINE",
                "Google API failed, Haversine fallback", 0, false);
    }
}
