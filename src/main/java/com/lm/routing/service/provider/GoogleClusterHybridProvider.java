package com.lm.routing.service.provider;

import com.lm.routing.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cluster-hybrid strategy: K-means clustering + inter/intra-cluster Google Matrix.
 * Reduces API cost by 90%+ compared to full Google matrix for large problems.
 *
 * Also provides boundary edge enrichment for post-TSP refinement.
 */
@Slf4j
@Service
public class GoogleClusterHybridProvider implements MatrixProvider {

    private final GoogleMapsApiService googleMapsApiService;
    private final KMeansClusteringService clusteringService;
    private final HaversineMatrixService haversineService;

    @Value("${routing.clustering.max-points-per-cluster:20}")
    private int maxPointsPerCluster;

    public GoogleClusterHybridProvider(GoogleMapsApiService googleMapsApiService,
                                        KMeansClusteringService clusteringService,
                                        HaversineMatrixService haversineService) {
        this.googleMapsApiService = googleMapsApiService;
        this.clusteringService = clusteringService;
        this.haversineService = haversineService;
    }

    @Override
    public String getName() {
        return "CLUSTER_HYBRID";
    }

    @Override
    public boolean isAvailable() {
        return googleMapsApiService.isAvailable();
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isRealRoad() {
        return true;
    }

    @Override
    public ProviderCapability getCapability() {
        return ProviderCapability.CLUSTER_HYBRID;
    }

    @Override
    public MatrixResult buildMatrix(List<GeoPoint> allPoints, GeoPoint warehouse) {
        int n = allPoints.size();

        // Only cluster the delivery stops (allPoints[1..n-1])
        List<GeoPoint> stops = allPoints.subList(1, n);
        KMeansClusteringService.ClusteringResult clustering =
                clusteringService.cluster(stops, warehouse);

        List<KMeansClusteringService.Cluster> clusters = clustering.clusters();
        List<GeoPoint> centroids = clustering.centroids();
        int k = clusters.size();

        log.info("Cluster-Hybrid: {} points → {} clusters (avg {} pts/cluster)",
                n - 1, k, String.format("%.1f", (double) (n - 1) / k));

        // Estimate API cost
        long interClusterElements = (long) k * k;
        long intraClusterElements = clusters.stream()
                .mapToLong(c -> (long) c.size() * c.size())
                .sum();
        long totalElements = interClusterElements + intraClusterElements;
        log.info("Google API elements: {} inter + {} intra = {} total (vs {} full)",
                interClusterElements, intraClusterElements, totalElements,
                (long) n * n);

        // Build the full assembled matrix (warehouse + stops)
        double[][] matrix = new double[n][n];

        // Warehouse-to-stop distances via Google (batch)
        fillWarehouseDistances(matrix, allPoints, warehouse);

        // Inter-cluster via centroids
        double[][] interMatrix = googleMapsApiService.computeSubMatrix(centroids, centroids);
        if (interMatrix == null) {
            log.warn("Inter-cluster matrix failed, falling back to Haversine");
            return new MatrixResult(haversineService.buildMatrix(allPoints),
                    "CLUSTER_FALLBACK_TO_HAVERSINE",
                    "Google API failed, Haversine fallback", 0, false);
        }

        // Intra-cluster matrices (sequential to respect API rate limits)
        for (int ci = 0; ci < k; ci++) {
            KMeansClusteringService.Cluster cluster = clusters.get(ci);
            List<GeoPoint> clusterPoints = cluster.pointIndices.stream()
                    .map(stops::get)
                    .collect(Collectors.toList());

            double[][] intra = googleMapsApiService.computeSubMatrix(clusterPoints, clusterPoints);
            if (intra == null) continue;

            // Copy intra-cluster distances into the full matrix
            for (int a = 0; a < clusterPoints.size(); a++) {
                int globalA = cluster.pointIndices.get(a) + 1;
                for (int b = 0; b < clusterPoints.size(); b++) {
                    int globalB = cluster.pointIndices.get(b) + 1;
                    matrix[globalA][globalB] = intra[a][b];
                }
            }

            // Inter-cluster approximations: use centroid distances
            for (int cj = 0; cj < k; cj++) {
                if (ci == cj) continue;
                double centroidDist = interMatrix[ci][cj];

                KMeansClusteringService.Cluster other = clusters.get(cj);
                for (int a = 0; a < clusterPoints.size(); a++) {
                    int globalA = cluster.pointIndices.get(a) + 1;
                    for (int bIdx : other.pointIndices) {
                        int globalB = bIdx + 1;
                        matrix[globalA][globalB] = centroidDist;
                    }
                }
            }
        }

        // Build cluster assignment map
        int[] clusterAssignments = new int[n];
        clusterAssignments[0] = -1; // warehouse has no cluster
        for (int ci = 0; ci < k; ci++) {
            for (int idx : clusters.get(ci).pointIndices) {
                clusterAssignments[idx + 1] = ci;
            }
        }

        return new MatrixResult(matrix, "CLUSTER_HYBRID",
                String.format("Google: K=%d clusters, %d total elements", k, totalElements),
                totalElements, true, clusterAssignments, clusters);
    }

    /**
     * Enrich the assembled distance matrix with real Google road distances at
     * cluster boundary positions.
     */
    public int enrichBoundaryEdges(double[][] matrix, int[] tspOrder,
                                    List<GeoPoint> points, int[] clusterAssignments,
                                    List<KMeansClusteringService.Cluster> clusters,
                                    int candidateCount) {
        if (!googleMapsApiService.isAvailable()) {
            log.debug("Google Maps API not available, skipping boundary enrichment");
            return 0;
        }

        List<int[]> boundaries = new ArrayList<>();
        for (int i = 0; i < tspOrder.length - 1; i++) {
            int fromIdx = tspOrder[i];
            int toIdx = tspOrder[i + 1];
            int fromCluster = (fromIdx >= 0 && fromIdx < clusterAssignments.length)
                    ? clusterAssignments[fromIdx] : -1;
            int toCluster = (toIdx >= 0 && toIdx < clusterAssignments.length)
                    ? clusterAssignments[toIdx] : -1;

            if (fromCluster >= 0 && toCluster >= 0 && fromCluster != toCluster) {
                boundaries.add(new int[]{i, fromCluster, toCluster});
            }
        }

        if (boundaries.isEmpty()) {
            log.debug("No cluster boundaries found in TSP order");
            return 0;
        }

        log.info("Enriching {} cluster boundaries (candidateCount={}) with real distances",
                boundaries.size(), candidateCount);

        int enrichedEdges = 0;

        for (int[] boundary : boundaries) {
            int boundaryIdx = boundary[0];
            int clusterFrom = boundary[1];
            int clusterTo = boundary[2];

            List<Integer> exitCandidates = collectSegmentEnd(
                    tspOrder, boundaryIdx, clusterAssignments, clusterFrom, candidateCount, true);
            List<Integer> entryCandidates = collectSegmentEnd(
                    tspOrder, boundaryIdx + 1, clusterAssignments, clusterTo, candidateCount, false);

            if (exitCandidates.isEmpty() || entryCandidates.isEmpty()) continue;

            List<GeoPoint> exitPoints = exitCandidates.stream()
                    .map(points::get).collect(Collectors.toList());
            List<GeoPoint> entryPoints = entryCandidates.stream()
                    .map(points::get).collect(Collectors.toList());

            double[][] realDistances = googleMapsApiService.computeSubMatrix(exitPoints, entryPoints);
            if (realDistances == null) {
                log.debug("Google API failed for boundary {}→{}, skipping", clusterFrom, clusterTo);
                continue;
            }

            for (int ei = 0; ei < exitCandidates.size(); ei++) {
                int exitIdx = exitCandidates.get(ei);
                for (int ej = 0; ej < entryCandidates.size(); ej++) {
                    int entryIdx = entryCandidates.get(ej);
                    if (realDistances[ei][ej] > 0) {
                        matrix[exitIdx][entryIdx] = realDistances[ei][ej];
                        enrichedEdges++;
                    }
                }
            }

            double[][] reverseDistances = googleMapsApiService.computeSubMatrix(entryPoints, exitPoints);
            if (reverseDistances != null) {
                for (int ei = 0; ei < entryCandidates.size(); ei++) {
                    int entryIdx = entryCandidates.get(ei);
                    for (int ej = 0; ej < exitCandidates.size(); ej++) {
                        int exitIdx = exitCandidates.get(ej);
                        if (reverseDistances[ei][ej] > 0) {
                            matrix[entryIdx][exitIdx] = reverseDistances[ei][ej];
                            enrichedEdges++;
                        }
                    }
                }
            }

            log.debug("Boundary cluster{}→cluster{}: enriched {} edges ({}×{} candidates)",
                    clusterFrom, clusterTo, enrichedEdges,
                    exitCandidates.size(), entryCandidates.size());
        }

        log.info("Boundary enrichment complete: {} real distances written across {} boundaries",
                enrichedEdges, boundaries.size());
        return enrichedEdges;
    }

    /**
     * Collect the last N (or first N) points of a contiguous cluster segment in the TSP order.
     */
    private List<Integer> collectSegmentEnd(int[] tspOrder, int startIdx,
                                             int[] assignments, int targetCluster,
                                             int count, boolean reverse) {
        List<Integer> result = new ArrayList<>();
        int idx = startIdx;
        while (result.size() < count && idx >= 0 && idx < tspOrder.length) {
            int pointIdx = tspOrder[idx];
            int cluster = (pointIdx >= 0 && pointIdx < assignments.length)
                    ? assignments[pointIdx] : -1;
            if (cluster == targetCluster) {
                result.add(pointIdx);
            } else {
                break;
            }
            idx += reverse ? -1 : 1;
        }

        if (reverse) {
            java.util.Collections.reverse(result);
        }
        return result;
    }

    /**
     * Fill warehouse-to-stop distances using Google Matrix API.
     */
    private void fillWarehouseDistances(double[][] matrix, List<GeoPoint> points,
                                         GeoPoint warehouse) {
        int n = points.size();
        List<GeoPoint> warehouseList = List.of(warehouse);
        List<GeoPoint> stops = points.subList(1, n);

        double[][] whToStops = googleMapsApiService.computeSubMatrix(warehouseList, stops);
        double[][] stopsToWh = googleMapsApiService.computeSubMatrix(stops, warehouseList);

        if (whToStops != null && whToStops.length > 0) {
            for (int j = 0; j < whToStops[0].length; j++) {
                matrix[0][j + 1] = whToStops[0][j];
            }
        }
        if (stopsToWh != null) {
            for (int i = 0; i < stopsToWh.length; i++) {
                if (stopsToWh[i].length > 0) {
                    matrix[i + 1][0] = stopsToWh[i][0];
                }
            }
        }

        // Fill missing with Haversine
        for (int i = 1; i < n; i++) {
            if (matrix[0][i] == 0) {
                matrix[0][i] = HaversineMatrixService.haversineDistance(warehouse, points.get(i));
            }
            if (matrix[i][0] == 0) {
                matrix[i][0] = HaversineMatrixService.haversineDistance(points.get(i), warehouse);
            }
        }
    }
}
