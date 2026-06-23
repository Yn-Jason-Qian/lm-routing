package com.lm.routing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class KMeansClusteringServiceTest {

    private KMeansClusteringService service;

    @BeforeEach
    void setUp() {
        service = new KMeansClusteringService();
        // Inject a smaller maxPointsPerCluster for testing multi-cluster behavior
        try {
            var field = KMeansClusteringService.class.getDeclaredField("maxPointsPerCluster");
            field.setAccessible(true);
            field.set(service, 10);
        } catch (Exception e) {
            // use default
        }
    }

    @Test
    void cluster_singlePoint_shouldReturnOneCluster() {
        List<GeoPoint> points = List.of(new GeoPoint(31.23, 121.47));
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        assertNotNull(result);
        assertEquals(1, result.clusters().size());
        assertEquals(1, result.centroids().size());
        assertEquals(1, result.clusters().get(0).size());
        assertEquals(0, result.clusters().get(0).pointIndices.get(0));
    }

    @Test
    void cluster_pointsLessThanMaxPerCluster_shouldReturnOneCluster() {
        List<GeoPoint> points = generateGridPoints(5); // 5 points, max=10 → 1 cluster
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        assertNotNull(result);
        assertEquals(1, result.clusters().size());
        assertEquals(5, result.clusters().get(0).size());
        // All point indices should be present
        assertEquals(5, result.clusters().get(0).pointIndices.stream().distinct().count());
    }

    @Test
    void cluster_multipleClusters_shouldCreateExpectedNumberOfClusters() {
        // 25 points with max=10 → ceil(25/10)=3 clusters
        List<GeoPoint> points = generateGridPoints(25);
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        assertNotNull(result);
        assertEquals(3, result.clusters().size());
        assertEquals(3, result.centroids().size());

        // Total points across all clusters should equal input
        int total = result.clusters().stream().mapToInt(KMeansClusteringService.Cluster::size).sum();
        assertEquals(25, total);

        // Each cluster should not exceed maxPointsPerCluster (10)
        for (KMeansClusteringService.Cluster c : result.clusters()) {
            assertTrue(c.size() <= 10,
                    "Cluster size " + c.size() + " exceeds max 10");
        }
    }

    @Test
    void cluster_shouldCoverAllPointsExactlyOnce() {
        List<GeoPoint> points = generateGridPoints(15);
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        // Collect all point indices
        List<Integer> allIndices = result.clusters().stream()
                .flatMap(c -> c.pointIndices.stream())
                .sorted()
                .collect(Collectors.toList());

        // Should have exactly 0..14
        assertEquals(15, allIndices.size());
        for (int i = 0; i < 15; i++) {
            assertEquals(i, allIndices.get(i), "Missing point index " + i);
        }
    }

    @Test
    void cluster_shouldReturnCentroidsNearPoints() {
        List<GeoPoint> points = List.of(
                new GeoPoint(31.23, 121.47),
                new GeoPoint(31.24, 121.48),
                new GeoPoint(31.25, 121.49),
                new GeoPoint(30.00, 120.00),
                new GeoPoint(30.01, 120.01)
        );
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        assertNotNull(result);
        // With 5 points and max=10, k=1, all in one cluster
        assertEquals(1, result.clusters().size());

        // Centroid should be within the bounding box of all points
        GeoPoint centroid = result.centroids().get(0);
        assertTrue(centroid.getLat() >= 30.00 && centroid.getLat() <= 31.25);
        assertTrue(centroid.getLng() >= 120.00 && centroid.getLng() <= 121.49);
    }

    @Test
    void cluster_deterministicWithSameInput() {
        List<GeoPoint> points = generateGridPoints(30);
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result1 = service.cluster(points, warehouse);
        KMeansClusteringService.ClusteringResult result2 = service.cluster(points, warehouse);

        // Same input, same seed → same result
        assertEquals(result1.clusters().size(), result2.clusters().size());
        for (int i = 0; i < result1.clusters().size(); i++) {
            assertEquals(result1.clusters().get(i).size(), result2.clusters().get(i).size());
            assertEquals(result1.clusters().get(i).pointIndices, result2.clusters().get(i).pointIndices);
        }
    }

    @Test
    void cluster_pointsMoreThanMaxPerCluster_shouldNotExceedLimit() {
        // 50 points with max=10 → 5 clusters, each should be ≤ 10
        List<GeoPoint> points = generateGridPoints(50);
        GeoPoint warehouse = new GeoPoint(31.23, 121.47);

        KMeansClusteringService.ClusteringResult result = service.cluster(points, warehouse);

        for (KMeansClusteringService.Cluster c : result.clusters()) {
            assertTrue(c.size() <= 10,
                    "Cluster size " + c.size() + " exceeds max 10 after rebalancing");
        }

        int total = result.clusters().stream().mapToInt(KMeansClusteringService.Cluster::size).sum();
        assertEquals(50, total);
    }

    // ===== Helpers =====

    /**
     * Generate N points in a grid pattern around Shanghai.
     */
    private List<GeoPoint> generateGridPoints(int count) {
        List<GeoPoint> points = new ArrayList<>();
        int cols = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            double lat = 31.20 + (i / cols) * 0.01;
            double lng = 121.45 + (i % cols) * 0.01;
            points.add(new GeoPoint(lat, lng));
        }
        return points;
    }
}
