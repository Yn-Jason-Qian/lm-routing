package com.lm.routing.service.provider;

import com.lm.routing.service.GeoPoint;
import com.lm.routing.service.HaversineMatrixService;
import com.lm.routing.service.provider.aws.AwsMapsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AWS Maps waypoint enrichment strategy.
 *
 * Builds a free Haversine matrix, then uses AWS Location Service Route Calculator
 * to fetch real road distances and polylines after TSP solving.
 *
 * AWS does not offer a native Distance Matrix API, so this follows the same
 * pattern as AMap: Haversine approximation → TSP → route enrichment.
 *
 * Cost: ~$0.0005 per route calculation call — the cheapest map API option.
 * Priority 7: Lowest-cost paid option, useful as a fallback in AWS-hosted environments.
 */
@Slf4j
@Service
public class AwsMapsWaypointsProvider implements MatrixProvider {

    private final HaversineMatrixService haversineService;
    private final AwsMapsService awsMapsService;

    public AwsMapsWaypointsProvider(HaversineMatrixService haversineService,
                                     AwsMapsService awsMapsService) {
        this.haversineService = haversineService;
        this.awsMapsService = awsMapsService;
    }

    @Override
    public String getName() {
        return "AWS_ROUTES";
    }

    @Override
    public boolean isAvailable() {
        return awsMapsService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 7;
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
        log.info("AWS Routes: Haversine matrix for {} points (enrichment via AWS Route Calculator after TSP)",
                allPoints.size());
        double[][] matrix = haversineService.buildMatrix(allPoints);
        return new MatrixResult(matrix, "AWS_ROUTES",
                "Haversine free matrix + AWS Route Calculator enrichment",
                0, false);
    }

    /**
     * Expose the underlying AWS Maps service for post-TSP enrichment in RoutePlanService.
     */
    public AwsMapsService getAwsMapsService() {
        return awsMapsService;
    }
}
