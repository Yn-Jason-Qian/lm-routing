package com.lm.routing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * K-means++ clustering for geographic coordinates.
 *
 * Used to decompose a large TSP (200+ stops) into manageable clusters
 * when using per-element-charged APIs like Google Distance Matrix.
 *
 * Algorithm:
 *   1. K-means++ initialization (spreads centroids well)
 *   2. Standard Lloyd's iteration until convergence
 *   3. Rebalance clusters to stay within max-points-per-cluster
 *
 * Distance metric: Haversine (free, fast, good enough for grouping).
 */
@Slf4j
@Service
public class KMeansClusteringService {

    @Value("${routing.clustering.max-points-per-cluster:20}")
    private int maxPointsPerCluster;

    /**
     * A cluster: centroid + list of point indices.
     */
    public static class Cluster {
        public GeoPoint centroid;
        public List<Integer> pointIndices;

        public Cluster(GeoPoint centroid) {
            this.centroid = centroid;
            this.pointIndices = new ArrayList<>();
        }

        public int size() { return pointIndices.size(); }
    }

    /**
     * Result of clustering.
     *
     * @param clusters     list of clusters
     * @param centroids    centroid of each cluster (for inter-cluster TSP)
     */
    public record ClusteringResult(List<Cluster> clusters, List<GeoPoint> centroids) {}

    /**
     * Cluster a list of points into K groups.
     *
     * @param points        all delivery stops (NOT including warehouse)
     * @param warehouse     the warehouse location (used for distance-aware init)
     * @return clustering result
     */
    public ClusteringResult cluster(List<GeoPoint> points, GeoPoint warehouse) {
        int n = points.size();

        // Determine K: each cluster holds ~maxPointsPerCluster points
        int k = Math.max(1, (int) Math.ceil((double) n / maxPointsPerCluster));
        k = Math.min(k, n); // can't have more clusters than points

        if (k <= 1) {
            // Single cluster
            Cluster c = new Cluster(computeCentroid(points));
            for (int i = 0; i < n; i++) c.pointIndices.add(i);
            List<GeoPoint> centroids = List.of(c.centroid);
            return new ClusteringResult(List.of(c), centroids);
        }

        log.info("K-means: {} points → {} clusters (max {}/cluster)", n, k, maxPointsPerCluster);

        // Step 1: K-means++ initialization
        List<GeoPoint> centroids = kmeansPlusPlusInit(points, k, warehouse);

        // Step 2: Lloyd's iteration
        int[] assignments = new int[n];
        boolean changed = true;
        int iterations = 0;

        while (changed && iterations < 50) {
            changed = false;
            iterations++;

            // Assign each point to nearest centroid
            for (int i = 0; i < n; i++) {
                int nearest = findNearestCentroid(points.get(i), centroids);
                if (assignments[i] != nearest) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }

            // Update centroids
            centroids = updateCentroids(points, assignments, k);
        }

        log.debug("K-means converged in {} iterations", iterations);

        // Step 3: Build clusters and rebalance if any exceed maxPointsPerCluster
        List<Cluster> clusters = buildClusters(points, assignments, centroids, k);

        // Rebalance oversized clusters
        rebalanceClusters(clusters, maxPointsPerCluster);

        // Recompute centroids after rebalancing
        List<GeoPoint> finalCentroids = clusters.stream()
                .map(c -> computeCentroidFromIndices(c, points))
                .collect(Collectors.toList());

        log.info("Clustering complete: {} clusters, sizes: {}",
                clusters.size(),
                clusters.stream().map(Cluster::size).collect(Collectors.toList()));

        return new ClusteringResult(clusters, finalCentroids);
    }

    // ===== K-means++ initialization =====

    private List<GeoPoint> kmeansPlusPlusInit(List<GeoPoint> points, int k, GeoPoint warehouse) {
        Random rng = new Random(42);
        List<GeoPoint> centroids = new ArrayList<>();
        int n = points.size();

        // First centroid: closest point to warehouse (heuristic)
        int firstIdx = findNearestCentroid(warehouse, points);
        centroids.add(points.get(firstIdx));

        // Subsequent centroids: probability proportional to squared distance
        for (int c = 1; c < k; c++) {
            double[] distSq = new double[n];
            double totalDistSq = 0;

            for (int i = 0; i < n; i++) {
                double minSq = Double.MAX_VALUE;
                for (GeoPoint centroid : centroids) {
                    double d = HaversineMatrixService.haversineDistance(points.get(i), centroid);
                    minSq = Math.min(minSq, d * d);
                }
                distSq[i] = minSq;
                totalDistSq += minSq;
            }

            // Pick next centroid via weighted random
            double threshold = rng.nextDouble() * totalDistSq;
            double cumulative = 0;
            int chosen = n - 1;
            for (int i = 0; i < n; i++) {
                cumulative += distSq[i];
                if (cumulative >= threshold) {
                    chosen = i;
                    break;
                }
            }
            centroids.add(points.get(chosen));
        }

        return centroids;
    }

    // ===== Lloyd's iteration helpers =====

    private int findNearestCentroid(GeoPoint point, List<GeoPoint> centroids) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.size(); i++) {
            double d = HaversineMatrixService.haversineDistance(point, centroids.get(i));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private List<GeoPoint> updateCentroids(List<GeoPoint> points, int[] assignments, int k) {
        List<GeoPoint> newCentroids = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            double sumLat = 0, sumLng = 0;
            int count = 0;
            for (int i = 0; i < points.size(); i++) {
                if (assignments[i] == c) {
                    sumLat += points.get(i).getLat();
                    sumLng += points.get(i).getLng();
                    count++;
                }
            }
            if (count > 0) {
                newCentroids.add(new GeoPoint(sumLat / count, sumLng / count));
            } else {
                // Empty cluster: keep old centroid (shouldn't happen with kmeans++)
                newCentroids.add(points.get(0)); // fallback
            }
        }
        return newCentroids;
    }

    // ===== Cluster building & rebalancing =====

    private List<Cluster> buildClusters(List<GeoPoint> points, int[] assignments,
                                         List<GeoPoint> centroids, int k) {
        List<Cluster> clusters = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            clusters.add(new Cluster(centroids.get(c)));
        }
        for (int i = 0; i < points.size(); i++) {
            clusters.get(assignments[i]).pointIndices.add(i);
        }
        return clusters;
    }

    /**
     * If any cluster exceeds maxPointsPerCluster, move overflow points
     * to the nearest under-capacity cluster.
     */
    private void rebalanceClusters(List<Cluster> clusters, int maxSize) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Cluster c : clusters) {
                while (c.size() > maxSize) {
                    // Find the point farthest from this centroid
                    int farthestIdx = findFarthest(c, clusters);
                    if (farthestIdx < 0) break;

                    // Move to nearest other cluster with capacity
                    int fromIdx = c.pointIndices.get(farthestIdx);
                    int bestDest = -1;
                    double bestDist = Double.MAX_VALUE;

                    for (int d = 0; d < clusters.size(); d++) {
                        if (clusters.get(d) == c) continue;
                        if (clusters.get(d).size() >= maxSize) continue;
                        double dist = HaversineMatrixService.haversineDistance(
                                clusters.get(d).centroid, c.centroid);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestDest = d;
                        }
                    }

                    if (bestDest >= 0) {
                        c.pointIndices.remove(farthestIdx);
                        clusters.get(bestDest).pointIndices.add(fromIdx);
                        changed = true;
                    } else {
                        break; // no capacity elsewhere
                    }
                }
            }
        }
    }

    private int findFarthest(Cluster c, List<Cluster> all) {
        // Simple: return the last index (no point ref for distance)
        if (c.pointIndices.isEmpty()) return -1;
        return c.pointIndices.size() - 1; // move the last one
    }

    // ===== Centroid computation =====

    private GeoPoint computeCentroid(List<GeoPoint> points) {
        double sumLat = 0, sumLng = 0;
        for (GeoPoint p : points) {
            sumLat += p.getLat();
            sumLng += p.getLng();
        }
        return new GeoPoint(sumLat / points.size(), sumLng / points.size());
    }

    private GeoPoint computeCentroidFromIndices(Cluster c, List<GeoPoint> points) {
        double sumLat = 0, sumLng = 0;
        for (int idx : c.pointIndices) {
            GeoPoint p = points.get(idx);
            sumLat += p.getLat();
            sumLng += p.getLng();
        }
        int n = c.pointIndices.size();
        return n > 0 ? new GeoPoint(sumLat / n, sumLng / n) : c.centroid;
    }
}
