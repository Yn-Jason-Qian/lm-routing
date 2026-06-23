package com.lm.routing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds a full distance matrix between all geographic points using the
 * Haversine formula (great-circle distance on a sphere).
 *
 * Cost: O(n²) pure CPU computation, ~10ms for 200 points.
 * Accuracy: straight-line distance; typically 65-80% of real road distance.
 * Suitable for TSP ordering — the rank correlation with real road distances
 * is high enough (>0.9) for near-optimal route ordering.
 */
@Slf4j
@Service
public class HaversineMatrixService {

    /** Earth mean radius in meters (WGS-84). */
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * Build a full n×n distance matrix in meters.
     * matrix[i][j] = straight-line distance from point i to point j.
     */
    public double[][] buildMatrix(List<GeoPoint> points) {
        int n = points.size();
        double[][] matrix = new double[n][n];

        for (int i = 0; i < n; i++) {
            matrix[i][i] = 0.0;
            for (int j = i + 1; j < n; j++) {
                double d = haversineDistance(points.get(i), points.get(j));
                matrix[i][j] = d;
                matrix[j][i] = d;
            }
        }

        log.debug("Haversine matrix built: {} points, {} unique pairs computed", n, n * (n - 1L) / 2);
        return matrix;
    }

    /**
     * Haversine distance between two lat/lng points in meters.
     * d = 2R × arcsin(√(hav(Δlat) + cos(lat₁)·cos(lat₂)·hav(Δlng)))
     */
    public static double haversineDistance(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.getLat());
        double lat2 = Math.toRadians(b.getLat());
        double dLat = Math.toRadians(b.getLat() - a.getLat());
        double dLng = Math.toRadians(b.getLng() - a.getLng());

        double havDLat = hav(dLat);
        double havDLng = hav(dLng);

        double aVal = havDLat + Math.cos(lat1) * Math.cos(lat2) * havDLng;
        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.sqrt(aVal));
    }

    /**
     * Haversine function: hav(θ) = sin²(θ/2) = (1 - cos θ) / 2
     */
    private static double hav(double theta) {
        double s = Math.sin(theta / 2.0);
        return s * s;
    }

    /**
     * Convenience: build matrix from raw coordinate arrays.
     */
    public double[][] buildMatrixFromArrays(double[] lats, double[] lngs) {
        if (lats.length != lngs.length) {
            throw new IllegalArgumentException("lats and lngs must have same length");
        }
        int n = lats.length;
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            matrix[i][i] = 0.0;
            for (int j = i + 1; j < n; j++) {
                double d = haversineDistance(new GeoPoint(lats[i], lngs[i]),
                                             new GeoPoint(lats[j], lngs[j]));
                matrix[i][j] = d;
                matrix[j][i] = d;
            }
        }
        return matrix;
    }
}
