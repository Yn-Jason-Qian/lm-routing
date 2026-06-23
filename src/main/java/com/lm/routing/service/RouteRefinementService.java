package com.lm.routing.service;

import com.lm.routing.infrastructure.cache.DistanceCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Applies 2-opt local search to improve a TSP route using real road distances
 * (from cache, AMap API, or Haversine fallback).
 *
 * Two modes:
 * - Fast mode: uses a pre-computed Haversine matrix for instant distance lookups
 * - Full mode: checks Redis cache → AMap API → Haversine for each distance lookup
 *
 * Window size limits the search to nearby stops (e.g., ±10 positions),
 * keeping refinement cheap — O(n × window²) lookups.
 */
@Slf4j
@Service
public class RouteRefinementService {

    private final AmapRouteService amapRouteService;
    private final DistanceCacheService cacheService;

    @Value("${routing.refinement.2opt-window-size:10}")
    private int windowSize;

    public RouteRefinementService(AmapRouteService amapRouteService,
                                   DistanceCacheService cacheService) {
        this.amapRouteService = amapRouteService;
        this.cacheService = cacheService;
    }

    /**
     * Fast-path refinement using pre-computed Haversine matrix.
     * All distance lookups are O(1) array access — zero IO.
     */
    public int[] refineWithMatrix(int[] order, double[][] matrix) {
        if (order.length <= 3) return order;

        int[] current = Arrays.copyOf(order, order.length);
        int n = current.length;
        boolean improved = true;
        int iterations = 0;
        int maxIterations = 20;

        while (improved && iterations < maxIterations) {
            improved = false;
            iterations++;

            for (int i = 1; i < n - 2; i++) {
                for (int j = i + 1; j < Math.min(n - 1, i + windowSize + 1); j++) {
                    int a = current[i - 1], b = current[i];
                    int c = current[j], d = current[j + 1];

                    double currentDist = matrix[a][b] + matrix[c][d];
                    double newDist = matrix[a][c] + matrix[b][d];
                    double improvement = currentDist - newDist;

                    if (improvement > 0.01) {
                        reverse(current, i, j);
                        improved = true;
                    }
                }
            }
        }

        log.info("2-opt (matrix): {} iterations, {} nodes, window={}",
                iterations - 1, n, windowSize);
        return current;
    }

    /**
     * Full-mode refinement using Redis/API/Haversine distance lookups.
     * Slower but uses real road distances when available.
     */
    public int[] refine(int[] order, List<GeoPoint> points) {
        if (order.length <= 3) return order;

        int[] current = Arrays.copyOf(order, order.length);
        int n = current.length;
        boolean improved = true;
        int iterations = 0;
        int maxIterations = 20;

        while (improved && iterations < maxIterations) {
            improved = false;
            iterations++;

            for (int i = 1; i < n - 2; i++) {
                for (int j = i + 1; j < Math.min(n - 1, i + windowSize + 1); j++) {
                    double improvement = evaluate2OptSwap(i, j, current, points);
                    if (improvement > 0.01) {
                        reverse(current, i, j);
                        improved = true;
                        log.debug("2-opt swap ({},{}): {}m improvement", i, j,
                                String.format("%.1f", improvement));
                    }
                }
            }
        }

        log.info("2-opt (full): {} iterations, {} nodes", iterations - 1, n);
        return current;
    }

    /**
     * Cross-cluster 2-opt refinement that uses a wider search window for edges
     * that cross cluster boundaries, allowing the algorithm to find better
     * inter-cluster transitions that regular 2-opt might miss.
     *
     * Strategy:
     *   1. Identify which edges in the route cross cluster boundaries
     *   2. Run 2-opt with a wider window (windowSize × windowMultiplier)
     *      for edges adjacent to cluster boundaries
     *   3. Use normal window for intra-cluster edges
     *
     * This should be called AFTER the distance matrix has been enriched with
     * real road distances at cluster boundaries (see
     * {@link DistanceMatrixProvider#enrichBoundaryEdges}).
     *
     * @param order              the current route order
     * @param matrix             N×N distance matrix (should be enriched at boundaries)
     * @param clusterAssignments point-index → cluster-index map (-1 for warehouse)
     * @param windowMultiplier   factor to widen the 2-opt window at boundaries (default 3)
     * @return refined route order
     */
    public int[] refineCrossCluster(int[] order, double[][] matrix,
                                    int[] clusterAssignments, int windowMultiplier) {
        if (clusterAssignments == null) {
            log.debug("No cluster assignments, falling back to regular 2-opt");
            return refineWithMatrix(order, matrix);
        }

        if (order.length <= 3) return order;

        int n = order.length;

        // Identify cross-cluster edge positions
        boolean[] isCrossCluster = new boolean[n];
        int boundaryCount = 0;
        for (int i = 0; i < n - 1; i++) {
            int fromIdx = order[i];
            int toIdx = order[i + 1];
            int fromCluster = (fromIdx >= 0 && fromIdx < clusterAssignments.length)
                    ? clusterAssignments[fromIdx] : -1;
            int toCluster = (toIdx >= 0 && toIdx < clusterAssignments.length)
                    ? clusterAssignments[toIdx] : -1;

            if (fromCluster >= 0 && toCluster >= 0 && fromCluster != toCluster) {
                isCrossCluster[i] = true;
                boundaryCount++;
            }
        }

        if (boundaryCount == 0) {
            log.debug("No cross-cluster edges found, using regular 2-opt");
            return refineWithMatrix(order, matrix);
        }

        int wideWindow = windowSize * windowMultiplier;
        log.info("Cross-cluster 2-opt: {} boundaries, wideWindow={}, normalWindow={}",
                boundaryCount, wideWindow, windowSize);

        int[] current = Arrays.copyOf(order, order.length);
        boolean improved = true;
        int iterations = 0;
        int maxIterations = 20;

        while (improved && iterations < maxIterations) {
            improved = false;
            iterations++;

            // First pass: wide window for boundary-adjacent edges
            for (int i = 1; i < n - 2; i++) {
                // Use wider window if this edge or nearby edges cross boundaries
                boolean nearBoundary = isCrossCluster[i - 1] || isCrossCluster[i]
                        || (i + 1 < n && isCrossCluster[i + 1]);
                int effectiveWindow = nearBoundary ? wideWindow : windowSize;

                int jMax = Math.min(n - 1, i + effectiveWindow);
                for (int j = i + 1; j < jMax; j++) {
                    int a = current[i - 1], b = current[i];
                    int c = current[j], d = current[j + 1];

                    double currentDist = matrix[a][b] + matrix[c][d];
                    double newDist = matrix[a][c] + matrix[b][d];
                    double improvement = currentDist - newDist;

                    if (improvement > 0.01) {
                        reverse(current, i, j);
                        improved = true;
                    }
                }
            }
        }

        log.info("Cross-cluster 2-opt: {} iterations, {} nodes, {} boundaries",
                iterations - 1, n, boundaryCount);
        return current;
    }

    /**
     * Evaluate the distance delta of a 2-opt swap: reverse segment [i..j].
     * Current edges: (i-1→i) + (j→j+1)
     * New edges:     (i-1→j) + (i→j+1)
     */
    private double evaluate2OptSwap(int i, int j, int[] order, List<GeoPoint> points) {
        GeoPoint a = points.get(order[i - 1]);
        GeoPoint b = points.get(order[i]);
        GeoPoint c = points.get(order[j]);
        GeoPoint d = points.get(order[j + 1]);

        double currentDist = getRealDistance(a, b) + getRealDistance(c, d);
        double newDist = getRealDistance(a, c) + getRealDistance(b, d);

        return currentDist - newDist;
    }

    /**
     * Get real road distance between two points.
     * Redis → AMap API → Haversine × 1.4 fallback.
     */
    private double getRealDistance(GeoPoint a, GeoPoint b) {
        // 1. Try Redis cache
        Optional<DistanceCacheService.CachedDistance> cached =
                cacheService.get(a.getLat(), a.getLng(), b.getLat(), b.getLng());
        if (cached.isPresent()) {
            return cached.get().distanceMeters();
        }

        // 2. Try AMap API
        List<AmapRouteService.RouteSegmentInfo> segments =
                amapRouteService.fetchRouteSegments(
                        a.getLat(), a.getLng(),
                        b.getLat(), b.getLng(),
                        Collections.emptyList());

        if (!segments.isEmpty()) {
            long dist = segments.get(0).getDistanceMeters();
            long dur = segments.get(0).getDurationSeconds();
            cacheService.put(a.getLat(), a.getLng(), b.getLat(), b.getLng(), dist, dur);
            return dist;
        }

        // 3. Fallback
        return HaversineMatrixService.haversineDistance(a, b) * 1.4;
    }

    private void reverse(int[] arr, int i, int j) {
        while (i < j) {
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
            i++; j--;
        }
    }
}
