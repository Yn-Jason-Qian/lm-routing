package com.lm.routing.service.provider;

import com.lm.routing.service.KMeansClusteringService;

import java.util.List;

/**
 * Result of matrix building: the matrix itself plus metadata about the strategy used.
 *
 * @param matrix               N×N distance matrix in meters (warehouse at index 0)
 * @param strategy             strategy name (e.g., "OSRM", "MAPBOX", "BING")
 * @param detail               human-readable description of what happened
 * @param costElements          number of API cost elements consumed
 * @param isRealRoad            true if distances are real road (not Haversine)
 * @param clusterAssignments   point-index → cluster-index mapping (index 0 = warehouse, -1 for unassigned).
 *                             Null for non-cluster strategies.
 * @param clusterList           the K-means clusters. Null for non-cluster strategies.
 */
public record MatrixResult(
        double[][] matrix,
        String strategy,
        String detail,
        long costElements,
        boolean isRealRoad,
        int[] clusterAssignments,
        List<KMeansClusteringService.Cluster> clusterList
) {
    /** Backward-compatible constructor for non-cluster strategies. */
    public MatrixResult(double[][] matrix, String strategy, String detail,
                        long costElements, boolean isRealRoad) {
        this(matrix, strategy, detail, costElements, isRealRoad, null, null);
    }
}
